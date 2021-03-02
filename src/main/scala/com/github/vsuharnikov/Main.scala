package com.github.vsuharnikov

import java.net.InetSocketAddress
import java.util.concurrent.Executors

import com.github.vsuharnikov.node.NodeApi
import com.google.common.util.concurrent.ThreadFactoryBuilder
import io.netty.util.HashedWheelTimer
import org.slf4j.LoggerFactory
import scopt.{OParser, Read}
import sttp.client3.{HttpURLConnectionBackend, UriContext}
import sttp.model.Uri

import scala.concurrent.duration.{DurationInt, FiniteDuration}

object Main extends App {

  case class MainSettings(
    blockchainUpdateServers: Seq[InetSocketAddress] = Seq.empty,
    startHeight: Option[Int] = None,
    nodeHttpApi: Uri = uri"http://example.com:1234",
    connectTimeout: FiniteDuration = 2.seconds,
    reconnectDelay: FiniteDuration = 10.seconds,
    maxEventsDelay: FiniteDuration = 10.seconds
  )

  implicit val uriScoptRead: Read[Uri] = Read.stringRead.map(Uri.unsafeParse)

  implicit val inetSocketAddressScoptRead: Read[InetSocketAddress] = Read.stringRead.map { x =>
    val Array(host, port) = x.split(":")
    new InetSocketAddress(host, port.toIntOption.getOrElse(throw new IllegalArgumentException(s"Unexpected port: $port")))
  }

  val argsParser = {
    val builder = OParser.builder[MainSettings]
    import builder._
    OParser.sequence(
      programName("nodes-event-watch"),
      opt[Seq[InetSocketAddress]]('s', "servers")
        .text("Watched servers, e.g.: 127.0.0.1:6881,foo.bar.com:6800")
        .required()
        .action((x, c) => c.copy(blockchainUpdateServers = x)),
      opt[Uri]('n', "reference-node")
        .text("The reference Node HTTP API uri")
        .required()
        .action((x, c) => c.copy(nodeHttpApi = x)),
      opt[Int]('h', "start-height")
        .text("Start height, should be >= 1. Retrieved by the NODE API if not specified")
        .action((x, c) => c.copy(startHeight = Some(x))),
      opt[FiniteDuration]('c', "connect-timeout")
        .text("Time to wait for a connection")
        .action((x, c) => c.copy(connectTimeout = x)),
      opt[FiniteDuration]('r', "reconnect-delay")
        .text("The delay before reconnect")
        .action((x, c) => c.copy(reconnectDelay = x)),
      opt[FiniteDuration]('d', "max-events-delay")
        .text("The maximum delay between events")
        .action((x, c) => c.copy(maxEventsDelay = x))
    )
  }

  OParser.parse(argsParser, args, MainSettings()) match {
    case None => System.exit(1)
    case Some(settings) =>
      val log = LoggerFactory.getLogger("NodesEventsWatch")
      log.info(
        s"""Starting with settings:
           |servers: ${settings.blockchainUpdateServers.mkString(", ")}
           |start height: ${settings.startHeight}
           |connect timeout: ${settings.connectTimeout}
           |reconnect delay: ${settings.reconnectDelay}
           |max events delay: ${settings.maxEventsDelay}""".stripMargin
      )

      try {
        val executor = Executors.newCachedThreadPool(new ThreadFactoryBuilder().setNameFormat("grpc-%d").setDaemon(false).build())
        val timer = new HashedWheelTimer()

        val nodeApi = new NodeApi(settings.nodeHttpApi, HttpURLConnectionBackend())
        val startHeight = math.max(
          1,
          settings
            .startHeight
            .getOrElse(nodeApi.height().getOrElse(throw new RuntimeException("Can't get reference Node height")) - 15)
        )

        val watchSettings = Watch.Settings(
          fromHeight = startHeight,
          connectTimeout = settings.connectTimeout,
          reconnectDelay = settings.reconnectDelay,
          maxEventsDelay = settings.maxEventsDelay
        )

        val watchers = settings.blockchainUpdateServers.map { target =>
          new Watch(watchSettings, target, executor, timer, nodeApi)
        }

        Runtime.getRuntime.addShutdownHook(new Thread() {
          override def run(): Unit = {
            log.info("Closing")
            watchers.foreach(_.close())
            timer.stop()
            executor.shutdownNow()
          }
        })

        Thread.sleep(2000) // Otherwise the thread of an executor is not initialized and application shuts down
      } catch {
        case e: Throwable =>
          log.error("During execution", e)
          throw e
      }
  }
}
