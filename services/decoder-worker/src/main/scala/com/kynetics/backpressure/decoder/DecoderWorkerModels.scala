package com.kynetics.backpressure.decoder

import upickle.default.{ReadWriter, macroRW, read, readwriter, writeJs}

final case class DecoderConfig(
    scenarioId: String,
    workerQueueCapacity: Int,
    workerParallelism: Int,
    baseDecodeLatencyMs: Int,
    latencyJitterMs: Int
)
object DecoderConfig {
  given ReadWriter[DecoderConfig] = macroRW
}

final case class WorkloadJob(
    jobId: String,
    scenarioId: String,
    sequence: Int,
    expectedNumber: Int,
    imagePngBase64: String,
    createdAt: String
)
object WorkloadJob {
  given ReadWriter[WorkloadJob] = macroRW
}

final case class ProcessingMetadata(
    enqueueWaitMs: Int,
    decodeLatencyMs: Int,
    totalLatencyMs: Int,
    workerInstanceId: String,
    completedAt: String
)
object ProcessingMetadata {
  given ReadWriter[ProcessingMetadata] = macroRW
}

final case class DecoderResult(
    jobId: String,
    scenarioId: String,
    expectedNumber: Int,
    decodedNumber: Option[Int],
    confidence: Double,
    status: String,
    processing: ProcessingMetadata
)
object DecoderResult {
  given ReadWriter[DecoderResult] = readwriter[ujson.Value].bimap[DecoderResult](
    value =>
      ujson.Obj(
        "jobId" -> value.jobId,
        "scenarioId" -> value.scenarioId,
        "expectedNumber" -> value.expectedNumber,
        "decodedNumber" -> value.decodedNumber.map(ujson.Num(_)).getOrElse(ujson.Null),
        "confidence" -> value.confidence,
        "status" -> value.status,
        "processing" -> writeJs(value.processing)
      ),
    json =>
      DecoderResult(
        jobId = json("jobId").str,
        scenarioId = json("scenarioId").str,
        expectedNumber = json("expectedNumber").num.toInt,
        decodedNumber = decodeOptionalInt(json.obj.getOrElse("decodedNumber", ujson.Null)),
        confidence = json("confidence").num,
        status = json("status").str,
        processing = read[ProcessingMetadata](json("processing"))
      )
  )

  private def decodeOptionalInt(value: ujson.Value): Option[Int] = value match {
    case ujson.Null => None
    case number: ujson.Num => Some(number.value.toInt)
    case ujson.Arr(items) if items.isEmpty => None
    case ujson.Arr(items) if items.length == 1 => Some(items.head.num.toInt)
    case other => throw new IllegalArgumentException(s"Invalid decodedNumber payload: ${other.render()}")
  }
}

final case class MetricsSnapshot(
    service: String,
    timestamp: String,
    requestedRate: Double,
    acceptedRate: Double,
    completedRate: Double,
    inFlight: Int,
    queueDepth: Int,
    p95LatencyMs: Double,
    rejectedCount: Long,
    saturationState: String
)
object MetricsSnapshot {
  given ReadWriter[MetricsSnapshot] = macroRW
}

final case class ConfigApplied(
    scenarioId: String,
    status: String
)
object ConfigApplied {
  given ReadWriter[ConfigApplied] = macroRW
}

final case class AdmissionAccepted(
    jobId: String,
    scenarioId: String,
    admission: String,
    queueDepth: Int
)
object AdmissionAccepted {
  given ReadWriter[AdmissionAccepted] = macroRW
}

final case class AdmissionRejected(
    status: String,
    reason: String,
    queueDepth: Int
)
object AdmissionRejected {
  given ReadWriter[AdmissionRejected] = macroRW
}

final case class ApiError(
    status: String,
    reason: String
)
object ApiError {
  given ReadWriter[ApiError] = macroRW
}
