package com.kynetics.backpressure.controlplane

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

class NumberRangePayload {
    var min: Int = 0
    var max: Int = 0

    fun copyPayload(): NumberRangePayload =
        NumberRangePayload().also {
            it.min = min
            it.max = max
        }
}

@JsonIgnoreProperties(ignoreUnknown = false)
class ScenarioConfigPayload {
    var scenarioId: String = ""
    var targetJobsPerSecond: Double = 0.0
    var numberRange: NumberRangePayload? = null
    var imageWidthPx: Int = 0
    var imageHeightPx: Int = 0
    var maxInFlight: Int = 0
    var workerQueueCapacity: Int = 0
    var workerParallelism: Int = 0
    var baseDecodeLatencyMs: Int = 0
    var latencyJitterMs: Int = 0

    fun copyPayload(): ScenarioConfigPayload =
        ScenarioConfigPayload().also {
            it.scenarioId = scenarioId
            it.targetJobsPerSecond = targetJobsPerSecond
            it.numberRange = numberRange?.copyPayload()
            it.imageWidthPx = imageWidthPx
            it.imageHeightPx = imageHeightPx
            it.maxInFlight = maxInFlight
            it.workerQueueCapacity = workerQueueCapacity
            it.workerParallelism = workerParallelism
            it.baseDecodeLatencyMs = baseDecodeLatencyMs
            it.latencyJitterMs = latencyJitterMs
        }

    fun toDecoderConfigRequest(): DecoderConfigRequest =
        DecoderConfigRequest(
            scenarioId = scenarioId,
            workerQueueCapacity = workerQueueCapacity,
            workerParallelism = workerParallelism,
            baseDecodeLatencyMs = baseDecodeLatencyMs,
            latencyJitterMs = latencyJitterMs,
        )

    fun toGeneratorConfigRequest(decoderBaseUrl: String): GeneratorConfigRequest =
        GeneratorConfigRequest(
            scenarioId = scenarioId,
            targetJobsPerSecond = targetJobsPerSecond,
            numberRange = requireNotNull(numberRange) { "numberRange must be present" }.copyPayload(),
            imageWidthPx = imageWidthPx,
            imageHeightPx = imageHeightPx,
            maxInFlight = maxInFlight,
            decoderBaseUrl = decoderBaseUrl,
        )
}

@JsonIgnoreProperties(ignoreUnknown = false)
class MetricsSnapshotPayload {
    var service: String = ""
    var timestamp: String = ""
    var requestedRate: Double = 0.0
    var acceptedRate: Double = 0.0
    var completedRate: Double = 0.0
    var inFlight: Int = 0
    var queueDepth: Int = 0
    var p95LatencyMs: Double = 0.0
    var rejectedCount: Int = 0
    var saturationState: String = "idle"
}

class ProcessingMetadataPayload {
    var enqueueWaitMs: Int = 0
    var decodeLatencyMs: Int = 0
    var totalLatencyMs: Int = 0
    var workerInstanceId: String = ""
    var completedAt: String = ""

    fun copyPayload(): ProcessingMetadataPayload =
        ProcessingMetadataPayload().also {
            it.enqueueWaitMs = enqueueWaitMs
            it.decodeLatencyMs = decodeLatencyMs
            it.totalLatencyMs = totalLatencyMs
            it.workerInstanceId = workerInstanceId
            it.completedAt = completedAt
        }
}

@JsonIgnoreProperties(ignoreUnknown = false)
class DecoderResultPayload {
    var jobId: String = ""
    var scenarioId: String = ""
    var expectedNumber: Int = 0
    var decodedNumber: Int? = null
    var confidence: Double = 0.0
    var status: String = ""
    var processing: ProcessingMetadataPayload = ProcessingMetadataPayload()

    fun copyPayload(): DecoderResultPayload =
        DecoderResultPayload().also {
            it.jobId = jobId
            it.scenarioId = scenarioId
            it.expectedNumber = expectedNumber
            it.decodedNumber = decodedNumber
            it.confidence = confidence
            it.status = status
            it.processing = processing.copyPayload()
        }
}

data class OverviewResponse(
    val scenario: ScenarioConfigPayload,
    val generatorMetrics: MetricsSnapshotPayload,
    val workerMetrics: MetricsSnapshotPayload,
    val recentResults: List<DecoderResultPayload>,
    val status: String,
)

data class ScenarioActionResponse(
    val status: String,
    val scenarioId: String,
)

data class DecoderConfigRequest(
    val scenarioId: String,
    val workerQueueCapacity: Int,
    val workerParallelism: Int,
    val baseDecodeLatencyMs: Int,
    val latencyJitterMs: Int,
)

data class GeneratorConfigRequest(
    val scenarioId: String,
    val targetJobsPerSecond: Double,
    val numberRange: NumberRangePayload,
    val imageWidthPx: Int,
    val imageHeightPx: Int,
    val maxInFlight: Int,
    val decoderBaseUrl: String,
)

@JsonIgnoreProperties(ignoreUnknown = false)
class DownstreamStatusPayload {
    var scenarioId: String = ""
    var status: String = ""
}

enum class ControlPlaneStatus(val value: String) {
    IDLE("idle"),
    RUNNING("running"),
    STOPPING("stopping"),
}

fun defaultScenario(): ScenarioConfigPayload =
    ScenarioConfigPayload().also {
        it.scenarioId = "happy-path-demo"
        it.targetJobsPerSecond = 6.0
        it.numberRange = NumberRangePayload().also { range ->
            range.min = 1
            range.max = 999
        }
        it.imageWidthPx = 96
        it.imageHeightPx = 48
        it.maxInFlight = 8
        it.workerQueueCapacity = 24
        it.workerParallelism = 4
        it.baseDecodeLatencyMs = 90
        it.latencyJitterMs = 20
    }
