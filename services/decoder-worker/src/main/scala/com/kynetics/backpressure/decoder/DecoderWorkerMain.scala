package com.kynetics.backpressure.decoder

import com.typesafe.config.ConfigFactory

object DecoderWorkerMain {
  def main(args: Array[String]): Unit = {
    val config = ConfigFactory.load()
    val initialConfig = DecoderConfig(
      scenarioId = config.getString("backpressure.decoder.default-scenario-id"),
      workerQueueCapacity = config.getInt("backpressure.decoder.worker-queue-capacity"),
      workerParallelism = config.getInt("backpressure.decoder.worker-parallelism"),
      baseDecodeLatencyMs = config.getInt("backpressure.decoder.base-decode-latency-ms"),
      latencyJitterMs = config.getInt("backpressure.decoder.latency-jitter-ms")
    )
    val recentResultsLimit = config.getInt("backpressure.decoder.recent-results-limit")
    val port = config.getInt("backpressure.http.port")
    val service = DecoderWorkerService.start(port, initialConfig, recentResultsLimit)

    Runtime.getRuntime.addShutdownHook(
      new Thread(() => service.close(), "decoder-worker-shutdown")
    )

    println(s"decoder-worker listening on port ${service.port}")
  }
}
