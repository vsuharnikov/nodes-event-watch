package com.github.vsuharnikov

import java.net.InetSocketAddress
import java.util.concurrent.Executors

import com.github.vsuharnikov.node.NodeApi
import com.google.common.util.concurrent.ThreadFactoryBuilder
import io.netty.util.HashedWheelTimer
import org.slf4j.LoggerFactory
import pureconfig._
import pureconfig.generic.auto._
import scopt.{OParser, Read}
import sttp.client3.HttpURLConnectionBackend
import sttp.model.Uri

import scala.concurrent.duration.FiniteDuration

object Main extends App {

  case class MainSettings(
    blockchainUpdateServers: Seq[InetSocketAddress],
    startHeight: Option[Int],
    nodeHttpApi: Uri,
    connectTimeout: FiniteDuration,
    reconnectDelay: FiniteDuration,
    maxEventsDelay: FiniteDuration,
    maxRollback: Int,
    strikes: Int
  )

  implicit val uriPureConfigReader: ConfigReader[Uri] = ConfigReader.stringConfigReader.map(Uri.unsafeParse)

  implicit val inetSocketAddressPureConfigReader: ConfigReader[InetSocketAddress] = ConfigReader.stringConfigReader.map { x =>
    val Array(host, port) = x.split(":")
    new InetSocketAddress(host, port.toIntOption.getOrElse(throw new IllegalArgumentException(s"Unexpected port: $port")))
  }

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
        .action((x, c) => c.copy(blockchainUpdateServers = x)),
      opt[Uri]('n', "reference-node")
        .text("The reference Node HTTP API uri")
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
        .action((x, c) => c.copy(maxEventsDelay = x)),
      opt[Int]('s', "strikes")
        .text("The number of failures after which displayed an error")
        .action((x, c) => c.copy(strikes = x))
    )
  }

  val initialSettings = ConfigSource.defaultApplication.at("nodes-event-watch").load[MainSettings] match {
    case Right(x) => x
    case Left(e) =>
      System.err.println(s"Can't load settings ${e.prettyPrint()}")
      System.exit(1)
      throw new RuntimeException("Won't happen")
  }

  OParser.parse(argsParser, args, initialSettings) match {
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
          maxEventsDelay = settings.maxEventsDelay,
          maxRollback = settings.maxRollback,
          strikes = settings.strikes
        )

        val watches = settings.blockchainUpdateServers.map { target =>
          new Watch(watchSettings, target, executor, timer, nodeApi)
        }

        Runtime.getRuntime.addShutdownHook(new Thread() {
          override def run(): Unit = {
            log.info("Closing")
            watches.foreach(_.close())
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
