package com.kynetics.backpressure.generator

import upickle.default.{ReadWriter, macroRW}

final case class NumberRange(min: Int, max: Int)
object NumberRange:
  given ReadWriter[NumberRange] = macroRW

final case class GeneratorConfig(
    scenarioId: String,
    targetJobsPerSecond: Double,
    numberRange: NumberRange,
    imageWidthPx: Int,
    imageHeightPx: Int,
    maxInFlight: Int,
    decoderBaseUrl: String
)
object GeneratorConfig:
  given ReadWriter[GeneratorConfig] = macroRW

final case class WorkloadJob(
    jobId: String,
    scenarioId: String,
    sequence: Long,
    expectedNumber: Int,
    imagePngBase64: String,
    createdAt: String
)
object WorkloadJob:
  given ReadWriter[WorkloadJob] = macroRW

final case class MetricsSnapshot(
    service: String,
    timestamp: String,
    requestedRate: Double,
    acceptedRate: Double,
    completedRate: Double,
    inFlight: Int,
    queueDepth: Int,
    p95LatencyMs: Double,
    rejectedCount: Int,
    saturationState: String
)
object MetricsSnapshot:
  given ReadWriter[MetricsSnapshot] = macroRW

final case class ConfigureResponse(scenarioId: String, status: String)
object ConfigureResponse:
  given ReadWriter[ConfigureResponse] = macroRW

final case class StartStopResponse(status: String)
object StartStopResponse:
  given ReadWriter[StartStopResponse] = macroRW

final case class HttpError(statusCode: Int, message: String)

final case class ErrorResponse(error: String)
object ErrorResponse:
  given ReadWriter[ErrorResponse] = macroRW
