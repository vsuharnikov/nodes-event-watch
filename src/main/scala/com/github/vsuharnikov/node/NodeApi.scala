package com.github.vsuharnikov.node

import sttp.client3._
import sttp.client3.playJson._
import sttp.model.Uri

class NodeApi(nodeHttpApi: Uri, httpBackend: SttpBackend[Identity, Any]) {

  def blockAt(height: Int): Either[Throwable, BlockHttpResponse] =
    try quickRequest
      .get(uri"$nodeHttpApi/blocks/at/$height")
      .response(asJson[BlockHttpResponse])
      .send(httpBackend)
      .body
    catch { case e: Throwable => Left(e) }

  def height(): Either[Throwable, Int] =
    try quickRequest
      .get(uri"$nodeHttpApi/blocks/height")
      .response(asJson[HeightHttpResponse])
      .send(httpBackend)
      .body
      .map(_.height)
    catch { case e: Throwable => Left(e) }

}
