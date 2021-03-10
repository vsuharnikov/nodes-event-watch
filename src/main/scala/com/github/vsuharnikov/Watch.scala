package com.github.vsuharnikov

import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicInteger

import com.github.vsuharnikov.Watch.{RestartException, Settings}
import com.github.vsuharnikov.node.NodeApi
import com.wavesplatform.events.api.grpc.protobuf.{BlockchainUpdatesApiGrpc, SubscribeEvent, SubscribeRequest}
import com.wavesplatform.events.protobuf.BlockchainUpdated.Append.Body
import com.wavesplatform.events.protobuf.BlockchainUpdated.Update
import io.grpc.netty.NettyChannelBuilder
import io.grpc.stub.{ClientCallStreamObserver, ClientCalls, ClientResponseObserver}
import io.grpc.{CallOptions, ManagedChannel}
import io.netty.channel.ChannelOption
import io.netty.util.{Timeout, Timer}
import org.slf4j.LoggerFactory

import scala.concurrent.duration.FiniteDuration
import scala.util.control.NoStackTrace

class Watch(
  settings: Settings,
  target: InetSocketAddress,
  executorService: ExecutorService,
  timer: Timer,
  nodeApi: NodeApi
) extends AutoCloseable {

  private val log = LoggerFactory.getLogger(s"Watch[${target.getHostString}]")

  private val strikes = new AtomicInteger(settings.strikes)
  @volatile private var lastHeight = settings.fromHeight
  @volatile private var lastTxId = ""

  @volatile private var timeout: Option[Timeout] = None
  @volatile private var currentChannel = connect()

  override def close(): Unit = currentChannel.shutdownNow()

  private def reconnect(): Unit = {
    currentChannel.shutdownNow()

    timer.newTimeout(
      (_: Timeout) => currentChannel = connect(),
      settings.reconnectDelay.length,
      settings.reconnectDelay.unit
    )
  }

  private def connect(): ManagedChannel = {
    val h = safeHeight(lastHeight)
    log.info(s"Starting from $h")

    lastTxId = ""
    lastHeight = h
    strikes.set(settings.strikes)

    val channel = NettyChannelBuilder
      .forAddress(target)
      .executor(executorService)
      .withOption[Integer](ChannelOption.CONNECT_TIMEOUT_MILLIS, settings.connectTimeout.toMillis.toInt)
      .usePlaintext()
      .build()

    val call = channel.newCall(BlockchainUpdatesApiGrpc.METHOD_SUBSCRIBE, CallOptions.DEFAULT.withWaitForReady())
    ClientCalls.asyncServerStreamingCall(
      call,
      SubscribeRequest(fromHeight = h),
      new ClientResponseObserver[SubscribeRequest, SubscribeEvent] {
        private var requestStream: Option[ClientCallStreamObserver[SubscribeRequest]] = None

        override def beforeStart(requestStream: ClientCallStreamObserver[SubscribeRequest]): Unit = {
          this.requestStream = Some(requestStream)
          requestStream.setOnReadyHandler(() => reschedule())
        }

        override def onNext(evt: SubscribeEvent): Unit = {
          reschedule()
          lastHeight = evt.getUpdate.height
          lastTxIdOf(evt).foreach(lastTxId = _)
          strikes.set(settings.strikes)
          log.debug(shortened(evt))
        }

        override def onError(e: Throwable): Unit = {
          e.getCause match {
            case RestartException => // Not interesting
            case _ => log.error("Error", e)
          }
          reconnect()
        }

        override def onCompleted(): Unit = {
          log.error("Unexpected completed")
          reconnect()
        }

        private def reschedule(): Unit = {
          timeout.foreach(_.cancel())
          timeout = Some(timer.newTimeout(
            { (_: Timeout) =>
              val failed = lastTxId == "" || hasHigherBlock() || hasNextTx(lastTxId, lastHeight)
              if (failed) {
                val updatedStrikes = strikes.decrementAndGet()
                if (updatedStrikes <= 0) {
                  log.error("The check is timed out")
                  requestStream.foreach(_.cancel("Restarting", RestartException))
                } else {
                  log.warn(s"Failures $updatedStrikes/${settings.strikes}")
                  reschedule()
                }
              }
            },
            settings.maxEventsDelay.length,
            settings.maxEventsDelay.unit
          ))
        }
      }
    )

    channel
  }

  private def hasHigherBlock(): Boolean =
    nodeApi.height() match {
      case Left(e) =>
        log.error(s"Failed to ask height", e)
        false // We don't know, does check fail or not
      case Right(x) =>
        log.info(s"Reference height: $x")
        x > lastHeight
    }

  private def hasNextTx(txId: String, atHeight: Int): Boolean =
    nodeApi.blockAt(atHeight) match {
      case Left(e) =>
        log.error(s"Failed to ask block $atHeight", e)
        false // We don't know, does check fail or not
      case Right(x) =>
        val lastReferenceTxId = x.transactions.last.id
        log.info(s"Last known tx: ${txId.take(5)}, last reference tx: ${lastReferenceTxId.take(5)}, reference block: $x")
        lastReferenceTxId != txId
    }

  private def safeHeight(height: Int): Int = math.max(1, height - settings.maxRollback)

  private def shortened(evt: SubscribeEvent): String = {
    val update = evt.getUpdate
    def ref = s"h=${update.height}, id=${Base58.encode(update.id.toByteArray).take(5)}"
    update.update match {
      case Update.Empty => "empty"
      case Update.Rollback(x) => s"rollback tpe=${x.`type`}, $ref"
      case Update.Append(x) =>
        val tpe = x.body match {
          case Body.Empty => "empty"
          case _: Body.Block => "f"
          case _: Body.MicroBlock => "m"
        }
        s"append tpe=$tpe, $ref"
    }
  }

  private def lastTxIdOf(evt: SubscribeEvent): Option[String] = evt.getUpdate.update match {
    case Update.Empty => None
    case Update.Rollback(_) => None
    case Update.Append(x) => x.transactionIds.lastOption.map(id => Base58.encode(id.toByteArray))
  }

}

object Watch {

  case class Settings(
    fromHeight: Int,
    connectTimeout: FiniteDuration,
    reconnectDelay: FiniteDuration,
    maxEventsDelay: FiniteDuration,
    maxRollback: Int,
    strikes: Int
  )

  private object RestartException extends RuntimeException with NoStackTrace
}
