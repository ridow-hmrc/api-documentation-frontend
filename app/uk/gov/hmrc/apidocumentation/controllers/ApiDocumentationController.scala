/*
 * Copyright 2023 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.apidocumentation.controllers

import java.time.{Clock, Instant}
import javax.inject.{Inject, Singleton}
import scala.concurrent.Future.successful
import scala.concurrent.{ExecutionContext, Future}

import controllers.Assets
import org.apache.pekko.stream.Materializer

import play.api.i18n.MessagesProvider
import play.api.mvc._
import uk.gov.hmrc.apiplatform.modules.apis.domain.models._
import uk.gov.hmrc.apiplatform.modules.common.domain.models.ApiVersionNbr
import uk.gov.hmrc.http.NotFoundException
import uk.gov.hmrc.play.bootstrap.frontend.controller.FrontendController

import uk.gov.hmrc.apidocumentation.ErrorHandler
import uk.gov.hmrc.apidocumentation.config.ApplicationConfig
import uk.gov.hmrc.apidocumentation.connectors.DownloadConnector
import uk.gov.hmrc.apidocumentation.models.{DocumentationItem, _}
import uk.gov.hmrc.apidocumentation.services._
import uk.gov.hmrc.apidocumentation.util.ApplicationLogger
import uk.gov.hmrc.apidocumentation.views.html._
import uk.gov.hmrc.apidocumentation.views.html.openapispec.ParentPageOuter

@Singleton
class ApiDocumentationController @Inject() (
    apiDefinitionService: ApiDefinitionService,
    val navigationService: NavigationService,
    loggedInUserService: LoggedInUserService,
    errorHandler: ErrorHandler,
    mcc: MessagesControllerComponents,
    apiIndexView: ApiIndexView,
    retiredVersionJumpView: RetiredVersionJumpView,
    apisFilteredView: ApisFilteredView,
    xmlDocumentationView: XmlDocumentationView,
    parentPage: ParentPageOuter,
    xmlServicesService: XmlServicesService,
    downloadConnector: DownloadConnector,
    assets: Assets
  )(implicit val ec: ExecutionContext,
    appConfig: ApplicationConfig,
    mat: Materializer
  ) extends FrontendController(mcc) with HeaderNavigation with PageAttributesHelper with HomeCrumb with DocumentationCrumb with ApplicationLogger {

  private lazy val cacheControlHeaders = "cache-control" -> "no-cache,no-store,max-age=0"
  private lazy val apiDocCrumb         = Crumb("API Documentation", routes.ApiDocumentationController.apiIndexPage(None, None, None).url)

  private lazy val v2ApiDocCrumb = Crumb(
    "API Documentation",
    uk.gov.hmrc.apidocumentation.v2.controllers.routes.FilteredDocumentationIndexController.apiListIndexPage(List.empty, List.empty).url
  )

  def apiIndexPage(service: Option[ServiceName], version: Option[ApiVersionNbr], filter: Option[String]): Action[AnyContent] = headerNavigation { implicit request => navLinks =>
    def pageAttributes(title: String = "API Documentation") =
      PageAttributes(title, breadcrumbs = Breadcrumbs(documentationCrumb, homeCrumb), headerLinks = navLinks, sidebarLinks = navigationService.sidebarNavigation())

    val params = for (a <- service; b <- version) yield (a, b)

    params match {
      case Some((service, version)) =>
        val url = routes.ApiDocumentationController.renderApiDocumentation(service, version, None, None).url
        Future.successful(Redirect(url))
      case None                     =>
        (for {
          userId  <- extractDeveloperIdentifier(loggedInUserService.fetchLoggedInUser())
          apis    <- apiDefinitionService.fetchAllDefinitions(userId)
          xmlApis <- xmlServicesService.fetchAllXmlApis()
        } yield {
          val apisByCategory = Documentation.groupedByCategory(apis, xmlApis, ServiceGuide.serviceGuides, RoadMap.roadMaps)

          filter match {
            case Some(f) => Ok(apisFilteredView(pageAttributes("Filtered API Documentation"), apisByCategory, DocumentationCategory.fromFilter(f)))
            case _       => Ok(apiIndexView(pageAttributes(), apisByCategory))
          }

        }) recoverWith {
          case e: Throwable =>
            logger.error("Could not load API Documentation service", e)
            errorHandler.internalServerErrorTemplate.map(InternalServerError(_))
        }
    }
  }

  def redirectToApiDocumentation(service: ServiceName, version: Option[ApiVersionNbr], cacheBuster: Option[Boolean]): Action[AnyContent] = version match {
    case Some(version) => Action.async {
        Future.successful(Redirect(routes.ApiDocumentationController.renderApiDocumentation(service, version, cacheBuster, None)))
      }
    case _             => redirectToCurrentApiDocumentation(service, cacheBuster)
  }

  private def redirectToCurrentApiDocumentation(service: ServiceName, cacheBuster: Option[Boolean]) = Action.async { implicit request =>
    (for {
      userId       <- extractDeveloperIdentifier(loggedInUserService.fetchLoggedInUser())
      extendedDefn <- apiDefinitionService.fetchExtendedDefinition(service, userId)
    } yield {
      extendedDefn.flatMap(_.userAccessibleApiDefinition.defaultVersion).fold(errorHandler.notFoundTemplate.map(NotFound(_))) { version =>
        successful(Redirect(routes.ApiDocumentationController.renderApiDocumentation(service, version.version, cacheBuster, None)))
      }
    }).flatten recoverWith {
      case _: NotFoundException => errorHandler.notFoundTemplate.map(NotFound(_))
      case e: Throwable         =>
        logger.error("Could not load API Documentation service", e)
        errorHandler.internalServerErrorTemplate.map(InternalServerError(_))
    }
  }

  def renderApiDocumentation(service: ServiceName, version: ApiVersionNbr, cacheBuster: Option[Boolean], useV2: Option[Boolean]): Action[AnyContent] =
    headerNavigation { implicit request => navLinks =>
      (for {
        userId           <- extractDeveloperIdentifier(loggedInUserService.fetchLoggedInUser())
        api              <- apiDefinitionService.fetchExtendedDefinition(service, userId)
        cacheBust         = bustCache(cacheBuster)
        apiDocumentation <- doRenderApiDocumentation(service, version, cacheBust, api, navLinks, userId, useV2)
      } yield apiDocumentation) recoverWith {
        case e: NotFoundException =>
          logger.info(s"Upstream request not found: ${e.getMessage}")
          errorHandler.notFoundTemplate.map(NotFound(_))
        case e: Throwable         =>
          logger.error("Could not load API Documentation service", e)
          errorHandler.internalServerErrorTemplate.map(InternalServerError(_))
      }
    }

  def bustCache(cacheBuster: Option[Boolean]): Boolean = cacheBuster.getOrElse(false)

  // scalastyle:off method.length
  private def doRenderApiDocumentation(
      service: ServiceName,
      version: ApiVersionNbr,
      cacheBuster: Boolean,
      apiOption: Option[ExtendedApiDefinition],
      navLinks: Seq[NavLink],
      developerId: Option[DeveloperIdentifier],
      useV2IndexPage: Option[Boolean]
    )(implicit request: Request[AnyContent],
      messagesProvider: MessagesProvider
    ): Future[Result] = {
    def makePageAttributes(apiDefinition: ExtendedApiDefinition, sidebarLinks: Seq[SidebarLink]) = {

      val breadcrumbs = if (useV2IndexPage.getOrElse(false)) Breadcrumbs(
        v2ApiDocCrumb,
        homeCrumb
      )
      else Breadcrumbs(
        apiDocCrumb,
        homeCrumb
      )

      PageAttributes(apiDefinition.name, breadcrumbs, navLinks, sidebarLinks)
    }

    def renderNotFoundPage = errorHandler.notFoundTemplate.map(NotFound(_))

    def redirectToLoginPage = {
      logger.info(s"redirectToLogin - access_uri ${routes.ApiDocumentationController.renderApiDocumentation(service, version, None, None).url}")
      Future.successful(Redirect("/developer/login").withSession(
        "access_uri" -> routes.ApiDocumentationController.renderApiDocumentation(service, version, None, None).url,
        "ts"         -> Instant.now(Clock.systemUTC).toEpochMilli.toString
      ))
    }

    def renderRetiredVersionJumpPage(api: ExtendedApiDefinition, useV2: Option[Boolean])(implicit request: Request[AnyContent], messagesProvider: MessagesProvider) = {
      val apiDefinition = api.userAccessibleApiDefinition

      Future.successful(Ok(retiredVersionJumpView(
        makePageAttributes(apiDefinition, navigationService.sidebarNavigation()),
        apiDefinition,
        useV2
      )))
    }

    def renderDocumentationPage(
        api: ExtendedApiDefinition,
        selectedVersion: ExtendedApiVersion,
        useV2: Option[Boolean]
      )(implicit request: Request[AnyContent],
        messagesProvider: MessagesProvider
      ): Future[Result] = {

      def withDefault(service: ServiceName)(file: String, label: String): Future[DocumentationItem] = {
        def resultToDocumentationItem(result: Result): Future[DocumentationItem] = {
          result.body.consumeData
            .map(bs => bs.utf8String)
            .map(text => DocumentationItem(label, text))
        }

        val findLocally: Future[DocumentationItem] = {
          assets.at("/public/common/docs", file, false)(request).flatMap(resultToDocumentationItem)
        }

        downloadConnector.fetch(service, ApiVersionNbr("common"), file)
          .flatMap(_.fold(findLocally)(resultToDocumentationItem))
      }

      def renderOas(categories: Seq[ApiCategory]): Future[Result] = {
        val withDefaultForService = withDefault(service) _

        val requiredFraudPrevention = (categories.contains(ApiCategory.VAT_MTD) || categories.contains(ApiCategory.INCOME_TAX_MTD)) && !api.isTestSupport

        for {
          overview        <- withDefaultForService("overview.md", "Overview")
          errors          <- withDefaultForService("errors.md", "Errors")
          testing         <- withDefaultForService("testing.md", "Testing")
          fraudPrevention <- withDefaultForService("fraud-prevention.md", "Fraud Prevention")
          versioning      <- withDefaultForService("versioning.md", "Versioning")
          markdownBlocks   = List(overview, errors, testing) ++ (if (requiredFraudPrevention) List(fraudPrevention) else List()) ++ List(versioning)
          attrs            = makePageAttributes(api, navigationService.openApiSidebarNavigation(markdownBlocks))

        } yield Ok(parentPage(attrs, markdownBlocks, api.name, api, selectedVersion, developerId.isDefined, useV2 = useV2)).withHeaders(cacheControlHeaders)
      }

      val categories = APICategoryFilters.categoryMap.getOrElse(api.name, Seq.empty) ++ api.categories
      renderOas(categories)
    }

    def findVersion(apiOption: Option[ExtendedApiDefinition]) =
      for {
        api        <- apiOption
        apiVersion <- api.versions.find(v => v.version == version)
        visibility <- VersionVisibility(apiVersion)
      } yield (api, apiVersion, visibility)

    findVersion(apiOption) match {
      case Some((api, selectedVersion, VersionVisibility(_, _, true, _))) if selectedVersion.status == ApiStatus.RETIRED => renderRetiredVersionJumpPage(api, useV2IndexPage)
      case Some((api, selectedVersion, VersionVisibility(_, _, true, _)))                                                => renderDocumentationPage(api, selectedVersion, useV2IndexPage)
      case Some((api, selectedVersion, VersionVisibility(ApiAccessType.PRIVATE, _, false, true)))                        => renderDocumentationPage(api, selectedVersion, useV2IndexPage)
      case Some((_, _, VersionVisibility(ApiAccessType.PRIVATE, false, _, _)))                                           => redirectToLoginPage
      case _                                                                                                             => renderNotFoundPage
    }
  }
  // scalastyle:on method.length

  def renderXmlApiDocumentation(name: String, useV2: Option[Boolean]): Action[AnyContent] = headerNavigation { implicit request => navLinks =>
    def makePageAttributes(apiDefinition: Documentation): PageAttributes = {
      val xmlCrumb    = Crumb(
        apiDefinition.name,
        routes.ApiDocumentationController.renderXmlApiDocumentation(apiDefinition.context, useV2).url
      )
      val breadcrumbs = if (useV2.getOrElse(false)) {
        Breadcrumbs(
          xmlCrumb,
          v2ApiDocCrumb,
          homeCrumb
        )
      } else Breadcrumbs(
        xmlCrumb,
        apiDocCrumb,
        homeCrumb
      )

      PageAttributes(apiDefinition.name, breadcrumbs, navLinks, navigationService.sidebarNavigation())
    }

    xmlServicesService.fetchXmlApi(name) flatMap {
      case Some(xmlApiDefinition) => successful(Ok(xmlDocumentationView(makePageAttributes(xmlApiDefinition), xmlApiDefinition)))
      case _                      => errorHandler.notFoundTemplate.map(NotFound(_))
    }
  }

  private def extractDeveloperIdentifier(f: Future[Option[Developer]]): Future[Option[DeveloperIdentifier]] = {
    f.map(o =>
      o.map(d => UuidIdentifier(d.userId))
    )
  }
}
