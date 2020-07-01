/*
 * Copyright 2020 HM Revenue & Customs
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

package uk.gov.hmrc.apidocumentation.views.helpers

import akka.http.scaladsl.model.{StatusCode, StatusCodes}
import org.raml.v2.api.model.v10.bodies.Response
import org.raml.v2.api.model.v10.common.Annotable
import org.raml.v2.api.model.v10.datamodel._
import org.raml.v2.api.model.v10.methods.Method
import org.raml.v2.api.model.v10.resources.Resource
import play.api.libs.json.Json
import play.libs.XML
import play.twirl.api.Html
import uk.gov.hmrc.apidocumentation.models.DocsVisibility.DocsVisibility
import uk.gov.hmrc.apidocumentation.models.JsonFormatters._
import uk.gov.hmrc.apidocumentation.models._

import scala.collection.JavaConverters._
import scala.language.reflectiveCalls
import scala.util.Try

object Slugify {
  def apply(text: String): String = makeSlug(text)

  def apply(obj: {def value(): String}): String = Option(obj).fold("")(obj => makeSlug(obj.value()))

  private def makeSlug(text: String) = Option(text).fold("") { obj =>
    obj.replaceAll("[^\\w\\s]", "").replaceAll("\\s+", "-").toLowerCase
  }
}


object Val {
  def apply(obj: String): String = Option(obj).getOrElse("")

  def apply(obj: Option[String]): String = obj.getOrElse("")

  def apply(obj: {def value(): String}): String = Option(obj).fold("")(_.value())
}

object HeaderVal {
  def apply(header: TypeDeclaration, version: String): String = {
    def replace(example: String) = {
      example.replace("application/vnd.hmrc.1.0", "application/vnd.hmrc." + version)
    }
    val example = Val(header.example)
    Val(header.displayName) match {
      case "Accept"=> replace(example)
      case "Content-Type" => replace(example)
      case _  => example
    }
  }

  def apply(header: TypeDeclaration2, version: String): String = {
    def replace(example: String) = {
      example.replace("application/vnd.hmrc.1.0", "application/vnd.hmrc." + version)
    }
    val exampleValue = header.example.value.getOrElse("") // TODO
    header.displayName match {
      case "Accept"=> replace(exampleValue)
      case "Content-Type" => replace(exampleValue)
      case _  => exampleValue
    }
  }
}

object FindProperty {
  def apply(typeInstance: TypeInstance, names: String*): Option[String] = {
    val properties = typeInstance.properties.asScala
    names match {
      case head +: Nil => {
        properties.find(_.name == head).map(scalarValue)
      }
      case head +: tail => {
        properties.find(_.name == head) match {
          case Some(property) => FindProperty(property.value, tail: _*)
          case _ => None
        }
      }
    }
  }

  private def scalarValue(property: TypeInstanceProperty): String = {
    if (!property.isArray && property.value.isScalar) property.value.value.toString else ""
  }
}

object Annotation {
  def apply(context: Annotable, names: String*): String = getAnnotation(context, names: _*).getOrElse("")

  def exists(context: Annotable, names: String*): Boolean = getAnnotation(context, names: _*).isDefined

  def optional(context: Annotable, names: String*): Option[String] = getAnnotation(context, names: _*).filterNot(_.isEmpty)

  def getAnnotation(context: Annotable, names: String*): Option[String] = {
    val matches = context.annotations.asScala.find { ann =>
      Option(ann.name).exists(stripNamespace(_) == names.head)
    }

    val out = for {
      m <- matches
      annotation = m.structuredValue
    } yield propertyForPath(annotation, names.tail.toList)

    out.flatten.map(_.toString)
  }

  private def stripNamespace(name: String): String = {
    name.replaceFirst("\\(.*\\.", "(")
  }

  private def propertyForPath(annotation: TypeInstance, path: List[AnyRef]): Option[AnyRef] =
    if (annotation.isScalar) scalarValueOf(annotation, path)
    else complexValueOf(annotation, path)

  private def complexValueOf(annotation: TypeInstance, path: List[AnyRef]): Option[AnyRef] =
    if (path.isEmpty) Option(annotation)
    else getProperty(annotation, path.head) match {
      case Some(ti: TypeInstance) => propertyForPath(ti, path.tail)
      case other => other
    }

  private def scalarValueOf(annotation: TypeInstance, path: List[AnyRef]): Option[AnyRef] =
    if (path.nonEmpty) throw new RuntimeException(s"Scalar annotations do not have properties")
    else Option(annotation.value())

  private def getProperty(annotation: TypeInstance, property: AnyRef) =
    annotation
      .properties.asScala
      .find(prop => prop.name == property)
      .map(ti => transformScalars(ti.value))

  private def transformScalars(value: TypeInstance) =
    if (value.isScalar) value.value() else value
}


object Markdown {

  def apply(text: String): Html = Html(process(text))

  def apply(text: Option[String]): Html = apply(text.getOrElse(""))

  def apply(obj: {def value(): String}): Html = Option(obj).fold(emptyHtml)(node => apply(node.value()))

  import com.github.rjeschke.txtmark.{Configuration, Processor}
  import org.markdown4j._

  private val emptyHtml = Html("")

  private def configuration =
    Configuration.builder
      .forceExtentedProfile
      .registerPlugins(new YumlPlugin, new WebSequencePlugin, new IncludePlugin)
      .setDecorator(new ExtDecorator()
        .addStyleClass("list list-bullet", "ul")
        .addStyleClass("list list-number", "ol")
        .addStyleClass("code--slim", "code")
        .addStyleClass("heading-large", "h1")
        .addStyleClass("heading-medium", "h2")
        .addStyleClass("heading-small", "h3")
        .addStyleClass("heading-small", "h4"))
      .setCodeBlockEmitter(new CodeBlockEmitter)

  private def process(text: String) = Processor.process(text, configuration.build)
}

case class ResourceGroup(name: Option[String] = None, description: Option[String] = None, resources: Seq[Resource] = Nil) {
  def +(resource: Resource) = {
    ResourceGroup(name, description, resources :+ resource)
  }
}
case class ResourceGroup2(name: Option[String] = None, description: Option[String] = None, resources: List[HmrcResource] = Nil) {
  def +(resource: HmrcResource) = {
    // TODO not efficient
    ResourceGroup2(name, description, resources :+ resource)
  }
}


object GroupedResources {
  def apply(resources: Seq[Resource]): Seq[ResourceGroup] = {
    group(flatten(resources)).filterNot(_.resources.length < 1)
  }

  def apply(resources: List[HmrcResource]): List[ResourceGroup2] = {
    def flatten(resources: List[HmrcResource], acc: List[HmrcResource]): List[HmrcResource] = {
      resources match {
        case Nil => acc.reverse
        case head :: tail =>
          // TODO - not efficient to right concat
          flatten(tail, flatten(head.children, head :: acc))
      }
    }

    def group(resources: List[HmrcResource], currentGroup: ResourceGroup2 = ResourceGroup2(), groups: List[ResourceGroup2] = Nil): List[ResourceGroup2] = {
      resources match {
        case head :: tail => {
          if (head.group.isDefined) {
            group(tail, ResourceGroup2(head.group.map(_.name), head.group.map(_.description), List(head)), groups :+ currentGroup)
          } else {
            group(tail, currentGroup + head, groups)
          }
        }
        case _ => groups :+ currentGroup
      }
    }

    group(flatten(resources, Nil)).filterNot(_.resources.length < 1)
  }

  private def group(resources: Seq[Resource], currentGroup: ResourceGroup = ResourceGroup(), groups: Seq[ResourceGroup] = Nil): Seq[ResourceGroup] = {
    resources match {
      case head +: tail => {
        if (Annotation.exists(head, "(group)")) {
          val groupName = Annotation(head, "(group)", "name")
          val groupDesc = Annotation(head, "(group)", "description")
          group(tail, ResourceGroup(Some(groupName), Some(groupDesc), Seq(head)), groups :+ currentGroup)
        } else {
          group(tail, currentGroup + head, groups)
        }
      }
      case _ => groups :+ currentGroup
    }
  }

  private def flatten(resources: Seq[Resource], acc: Seq[Resource] = Nil): Seq[Resource] = {
    resources match {
      case head +: tail => {
        flatten(tail, flatten(head.resources.asScala, acc :+ head))
      }
      case _ => acc
    }
  }
}

object Methods {
  private val correctOrder = Map(
    "get" -> 0, "post" -> 1, "put" -> 2, "delete" -> 3,
    "head" -> 4, "patch" -> 5, "options" -> 6
  )

  def apply(resource: Resource): List[Method] =
    resource.methods.asScala.toList.sortWith { (left, right) =>
      (for {
        l <- correctOrder.get(left.method)
        r <- correctOrder.get(right.method)
      } yield l < r).getOrElse(false)
    }
}

object Authorisation {
  def apply(method: HmrcMethod): (String, Option[String]) = {
    method.securedBy.fold( ("none", Option.empty[String] ) )( _ match {
      case SecurityScheme("OAuth 2.0", scope) => (("user", scope))
      case _ => ("application", None)
    })
  }

  def apply(method: Method): (String, Option[String]) = fetchAuthorisation(method)

  private def fetchAuthorisation(method: Method): (String, Option[String]) = {
    if (method.securedBy().asScala.nonEmpty) {
      method.securedBy.get(0).securityScheme.`type` match {
        case "OAuth 2.0" => ("user", Some(Annotation(method, "(scope)")))
        case _ => ("application", None)
      }
    } else {
      ("none", None)
    }
  }
}



object Responses {
  def success(method: Method) = method.responses.asScala.filter(isSuccessResponse)

  def error(method: Method) = method.responses.asScala.filter(isErrorResponse)

  private def isSuccessResponse(response: Response) = {
    val code = Val(response.code)
    code.startsWith("2") || code.startsWith("3")
  }

  private def isErrorResponse(response: Response) = {
    val code = Val(response.code)
    code.startsWith("4") || code.startsWith("5")
  }


  def success(method: HmrcMethod) = method.responses.filter(isSuccessResponse)

  def error(method: HmrcMethod) = method.responses.filter(isErrorResponse)

  private def isSuccessResponse(response: HmrcResponse) = {
    response.code.startsWith("2") || response.code.startsWith("3")
  }

  private def isErrorResponse(response: HmrcResponse) = {
    response.code.startsWith("4") || response.code.startsWith("5")
  }

}


object ErrorScenarios {
  def apply(method: Method): Seq[Map[String, String]] = {

    val errorScenarios = for {
      response <- Responses.error(method)
      body <- response.body.asScala
      example <- BodyExamples(body)
      scenarioDescription <- scenarioDescription(body, example)
      errorResponse <- errorResponse(example)
    } yield {
      errorResponse.code.map(code =>
        Map("scenario" -> scenarioDescription,
          "code" -> code,
          "httpStatus" -> response.code.value))
    }

    errorScenarios.flatten
  }

  private def errorResponse(bodyExample: BodyExample): Option[ErrorResponse] = {
    FindProperty(bodyExample.example.structuredValue, "value", "code")
      .orElse(FindProperty(bodyExample.example.structuredValue, "code"))
      .fold(responseFromBody(bodyExample))(code => Some(ErrorResponse(code = Some(code))))
  }

  private def errorResponse2(example: HmrcExampleSpec): Option[ErrorResponse] = {
    example.code.fold(responseFromBody2(example))(code => Some(ErrorResponse(code = Some(code))))
  }


  private def scenarioDescription(body: TypeDeclaration, example: BodyExample): Option[String] = {
    example.description()
      .orElse(Option(body.description).map(_.value))
  }
  private def responseFromBody(example: BodyExample): Option[ErrorResponse] = {
    responseFromJson(example).orElse(responseFromXML(example))
  }
  private def responseFromJson(example: BodyExample): Option[ErrorResponse] = {
    example.value.flatMap(v => Try(Json.parse(v).as[ErrorResponse]).toOption)
  }

  private def responseFromXML(example: BodyExample): Option[ErrorResponse] = {
    for {
      v <- example.value
      codes <- Try(XML.fromString(v).getElementsByTagName("code")).toOption
      first <- Option(codes.item(0))
    } yield {
      ErrorResponse(Some(first.getTextContent))
    }
  }

  private def responseFromBody2(example: HmrcExampleSpec): Option[ErrorResponse] = {
    responseFromJson2(example).orElse(responseFromXML2(example))
  }

  private def responseFromJson2(example: HmrcExampleSpec): Option[ErrorResponse] = {
    example.value.flatMap(v => Try(Json.parse(v).as[ErrorResponse]).toOption)
  }
  private def responseFromXML2(example: HmrcExampleSpec): Option[ErrorResponse] = {
    for {
      v <- example.value
      codes <- Try(XML.fromString(v).getElementsByTagName("code")).toOption
      first <- Option(codes.item(0))
    } yield {
      ErrorResponse(Some(first.getTextContent))
    }
  }


  def apply(method: HmrcMethod): Seq[Map[String, String]] = {

    val errorScenarios = for {
      response <- Responses.error(method)
      body <- response.body
      example <- BodyExamples(body)
      scenarioDescription <- scenarioDescription(body, example)
      errorResponse <- errorResponse2(example)
    } yield {
      errorResponse.code.map(code =>
        Map("scenario" -> scenarioDescription,
          "code" -> code,
          "httpStatus" -> response.code))
    }

    errorScenarios.flatten
  }

  private def scenarioDescription(body: TypeDeclaration2, example: BodyExample): Option[String] = {
    example.description
    .orElse(body.description)
  }

  private def scenarioDescription(body: TypeDeclaration2, example: HmrcExampleSpec): Option[String] = {
    example.description.orElse(body.description)
  }
}

case class BodyExample(example: ExampleSpec) {
  def description(): Option[String] = {
    FindProperty(example.structuredValue, "description", "value")
  }

  def documentation(): Option[String] = {
    if (Annotation.exists(example, "(documentation)")) {
      Option(Annotation(example, "(documentation)"))
    } else {
      None
    }
  }

  def code(): Option[String] = {
    FindProperty(example.structuredValue, "value", "code")
      .orElse(FindProperty(example.structuredValue, "code"))
  }

  def value() = {
    FindProperty(example.structuredValue, "value")
      .orElse(Some(example.value))
  }
}

object BodyExamples {
  def apply(body: TypeDeclaration): Seq[BodyExample] = {
    if (body.examples.size > 0) body.examples.asScala.toSeq.map(ex => BodyExample(ex)) else Seq(BodyExample(body.example))
  }

  def apply(body: TypeDeclaration2): Seq[HmrcExampleSpec] = {
    if (body.examples.size > 0) body.examples else Seq(body.example)
  }
}

object HttpStatus {
  def apply(statusCode: String): String = apply(statusCode.toInt)

  def apply(statusCode: Int): String = {

    val responseStatus: StatusCode = try {
       StatusCode.int2StatusCode(statusCode)
    } catch {
      case _ : RuntimeException => StatusCodes.custom(statusCode,"non-standard", "" )
    }

    s"$statusCode (${responseStatus.reason})"
  }
}

object AvailabilityPhrase {
  val yes = "Yes"
  val yesPrivateTrial = "Yes - private trial"
  val no = "No"
}

object EndpointsAvailable {
  def apply(availability: Option[APIAvailability]): String = availability match {
    case Some(APIAvailability(endpointsEnabled, access, _, authorised)) if endpointsEnabled => access.`type` match {
      case APIAccessType.PUBLIC => AvailabilityPhrase.yes
      case APIAccessType.PRIVATE if access.isTrial.getOrElse(false) => AvailabilityPhrase.yesPrivateTrial
      case APIAccessType.PRIVATE if authorised => AvailabilityPhrase.yes
      case _ => AvailabilityPhrase.no
    }
    case _ => AvailabilityPhrase.no
  }
}

object ShowBaseURL {
  def apply(availability: Option[APIAvailability]) =  EndpointsAvailable(availability) match {
    case AvailabilityPhrase.yes | AvailabilityPhrase.yesPrivateTrial => true
    case _ => false
  }
}

object VersionDocsVisible {
  def apply(availability: Option[VersionVisibility]): DocsVisibility = availability match {
    case Some(VersionVisibility(APIAccessType.PUBLIC, _, _, _)) => DocsVisibility.VISIBLE                     // PUBLIC
    case Some(VersionVisibility(APIAccessType.PRIVATE, true, true, _)) => DocsVisibility.VISIBLE              // PRIVATE, logged in, whitelisted (authorised)
    case Some(VersionVisibility(APIAccessType.PRIVATE, _, false, Some(true))) => DocsVisibility.OVERVIEW_ONLY // PRIVATE, trial, either not logged in or not whitelisted (authorised)
    case _ => DocsVisibility.NOT_VISIBLE
  }
}
