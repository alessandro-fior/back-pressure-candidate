package com.kynetics.backpressure.generator

object WorkloadGeneratorMain:
  def main(args: Array[String]): Unit =
    val service = WorkloadGeneratorService.fromReferenceConfig()
    sys.addShutdownHook(service.close())
    println(s"Phase 1B workload-generator listening on ${service.boundPort}")
    service.awaitShutdown()
