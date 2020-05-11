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

package uk.gov.hmrc.apidocumentation.services

import javax.inject.Inject

import play.api.mvc.Result
import uk.gov.hmrc.apidocumentation.connectors.DownloadConnector
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent._

class DownloadService @Inject()(downloadConnector: DownloadConnector) {

  def fetchResource(serviceName: String, version: String, resource: String)
                   (implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Result] = {
    downloadConnector.fetch(serviceName, version, resource)
  }

}
