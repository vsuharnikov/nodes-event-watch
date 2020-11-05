package com.github.vsuharnikov

import java.net.InetSocketAddress
import java.util.concurrent.Executors

import com.google.common.util.concurrent.ThreadFactoryBuilder
import com.wavesplatform.events.api.grpc.protobuf.{BlockchainUpdatesApiGrpc, SubscribeEvent, SubscribeRequest}
import io.grpc.netty.NettyChannelBuilder
import io.grpc.stub.StreamObserver
import org.slf4j.LoggerFactory
import scalapb.json4s.JsonFormat

object Main extends App {
  val restApiBaseUrl = "https://nodes.wavesnodes.com"
  val grpcApiSocketAddress = new InetSocketAddress("blockchain-updates.waves.exchange", 443)

  val log = LoggerFactory.getLogger("NodesEventsWatch")
  log.info("Started")

  val lastHeight = new BlockchainRestApi(restApiBaseUrl).getLastHeight

  val executor = Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setNameFormat("grpc-%d").setDaemon(false).build())

  val channel = NettyChannelBuilder
    .forAddress(grpcApiSocketAddress)
    .executor(executor)
    .usePlaintext()
    .build()

  Runtime.getRuntime.addShutdownHook(new Thread() {

    override def run(): Unit = {
      log.info("Closing")
      channel.shutdownNow()
      executor.shutdownNow()
      super.run()
    }

  })

  val service = BlockchainUpdatesApiGrpc.stub(channel)
  service.subscribe(
    SubscribeRequest(fromHeight = lastHeight - 1),
    new StreamObserver[SubscribeEvent] {
      private val log = LoggerFactory.getLogger("Events")

      override def onNext(value: SubscribeEvent): Unit = log.info(JsonFormat.toJsonString(value))
      override def onError(e: Throwable): Unit = log.error("Error", e)
      override def onCompleted(): Unit = log.info("Completed")
    }
  )

  Thread.sleep(2000) // Otherwise the thread of an executor is not initialized and application shuts down

}
