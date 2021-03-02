package com.github.vsuharnikov.node

import play.api.libs.json.{Json, OFormat}

case class HeightHttpResponse(height: Int)

object HeightHttpResponse {
  implicit val heightHttpResponseJsonFormat: OFormat[HeightHttpResponse] = Json.format[HeightHttpResponse]
}
