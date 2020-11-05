package com.github.vsuharnikov

import java.net.URI
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.{HttpClient, HttpRequest}
import java.nio.charset.StandardCharsets

import org.slf4j.LoggerFactory

class BlockchainRestApi(apiBaseUrl: String) {
  private val logger = LoggerFactory.getLogger("BlockchainRestApi")

  private val httpClient = HttpClient.newBuilder().build()

  def getLastHeight: Int = {
    val heightResponse = httpClient.send(
      HttpRequest.newBuilder(URI.create(s"$apiBaseUrl/blocks/height")).build(),
      BodyHandlers.ofString(StandardCharsets.UTF_8)
    )

    if (heightResponse.statusCode() != 200) throw new RuntimeException(s"${heightResponse.statusCode()}: ${heightResponse.body()}")

    val height = """^.+:(\d+).+$""".r.replaceAllIn(heightResponse.body(), "$1")
    logger.trace(s".getLastHeight: body=${heightResponse.body()}, height=$height")

    height.toInt
  }

}
