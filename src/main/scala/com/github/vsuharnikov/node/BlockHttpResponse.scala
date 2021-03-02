package com.github.vsuharnikov.node

import com.github.vsuharnikov.node.BlockHttpResponse.HttpTransaction
import play.api.libs.json.{Json, OFormat}

case class BlockHttpResponse(height: Int, transactions: List[HttpTransaction]) {
  override def toString: String = s"BlockHttpResponse($height, txs=${transactions.map(_.id.take(5)).mkString(", ")})"
}

object BlockHttpResponse {
  implicit val blockHttpResponseJsonFormat: OFormat[BlockHttpResponse] = Json.format[BlockHttpResponse]

  case class HttpTransaction(id: String)

  object HttpTransaction {
    implicit val httpTransactionJsonFormat: OFormat[HttpTransaction] = Json.format[HttpTransaction]
  }

}
