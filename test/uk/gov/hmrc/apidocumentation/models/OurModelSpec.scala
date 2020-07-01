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

package uk.gov.hmrc.apidocumentation.models

import uk.gov.hmrc.play.test.UnitSpec
import uk.gov.hmrc.ramltools.loaders.FileRamlLoader
import scala.util.Failure
import scala.util.Success
import uk.gov.hmrc.apidocumentation.services.RAML
import play.api.libs.json.Json
import uk.gov.hmrc.apidocumentation.views.helpers.ResourceGroup2

// TODO: Rebrand as wireModel spec
class OurModelSpec extends UnitSpec {
  "RAML to OurModel" should {
    "Simple.raml should parse title and version to our model" in {
      val raml = loadRaml("simple.raml")
      
      val ourModel = OurModel(raml)._2
      ourModel.title shouldBe "My simple title"
      ourModel.version shouldBe "My version"
    }

    "With single method" in {
      val raml = loadRaml("single-method.raml")
      
      val ourModel = OurModel(raml)._2
      ourModel.resourceGroups.size shouldBe 1

      // TODO: Check endpoint URL, description
      // TODO: Doesn't handle missing description (null pointer)
    }
  }

  object OurModelFormatters {
    implicit val hmrcExampleSpecJF = Json.format[HmrcExampleSpec]
    implicit val typeDeclaration2JF = Json.format[TypeDeclaration2]
    
    implicit val securitySchemeJF = Json.format[SecurityScheme]
    
    implicit val groupJF = Json.format[Group]
    implicit val hmrcResponseJF = Json.format[HmrcResponse]
    implicit val hmrcMethodJF = Json.format[HmrcMethod]
    implicit val hmrcResourceJF = Json.format[HmrcResource]

    implicit val documentationItemJF = Json.format[DocumentationItem]
    implicit val resourceGroup2JF = Json.format[ResourceGroup2]

    implicit val wireModelJF = Json.format[WireModel]
  }

  "What does the JSON look like?" in {
      import OurModelFormatters._

      val raml = loadRaml("simple.raml")
      
      val wireModel : WireModel = OurModel(raml)._2

      println(Json.prettyPrint(Json.toJson(wireModel)))
  }

  def loadRaml(filename: String) : RAML = {
    new FileRamlLoader().load(s"test/resources/raml/v2/$filename") match {
      case Failure(exception) => throw exception
      case Success(raml) => raml
    }
  }
}
