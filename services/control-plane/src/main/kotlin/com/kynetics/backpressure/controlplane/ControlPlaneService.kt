package com.kynetics.backpressure.controlplane

import org.springframework.beans.factory.annotation.Value
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestTemplate
import org.springframework.web.server.ResponseStatusException

@Service
class ControlPlaneService(
    private val restTemplate: RestTemplate,
    @Value("\${backpressure.generator.base-url}") private val generatorBaseUrl: String,
    @Value("\${backpressure.decoder.base-url}") private val decoderBaseUrl: String,
) {
    private val stateLock = Any()
    private var currentScenario: ScenarioConfigPayload = defaultScenario()
    private var currentStatus: ControlPlaneStatus = ControlPlaneStatus.IDLE

    fun getOverview(): OverviewResponse {
        val scenario = synchronized(stateLock) { currentScenario.copyPayload() }
        val generatorMetrics = fetchJson("$generatorBaseUrl/internal/generator/metrics", HttpMethod.GET, null, MetricsSnapshotPayload::class.java)
        val workerMetrics = fetchJson("$decoderBaseUrl/internal/decoder/metrics", HttpMethod.GET, null, MetricsSnapshotPayload::class.java)
        val recentResults = fetchJsonList("$decoderBaseUrl/internal/decoder/results/recent")
        val status = synchronized(stateLock) {
            if (
                currentStatus == ControlPlaneStatus.STOPPING &&
                generatorMetrics.saturationState == "idle" &&
                generatorMetrics.inFlight == 0 &&
                // The generator only reports the last queue depth observed from decoder responses.
                // Use decoder metrics to decide whether the downstream pipeline has fully drained.
                workerMetrics.saturationState == "idle" &&
                workerMetrics.inFlight == 0 &&
                workerMetrics.queueDepth == 0
            ) {
                currentStatus = ControlPlaneStatus.IDLE
            }
            currentStatus.value
        }

        return OverviewResponse(
            scenario = scenario,
            generatorMetrics = generatorMetrics,
            workerMetrics = workerMetrics,
            recentResults = recentResults,
            status = status,
        )
    }

    fun updateCurrentScenario(payload: ScenarioConfigPayload): ScenarioConfigPayload {
        validateScenario(payload)
        val normalized = payload.copyPayload()
        val applyLive = synchronized(stateLock) { currentStatus == ControlPlaneStatus.RUNNING }
        if (applyLive) {
            configureDownstreamScenario(normalized)
        }
        synchronized(stateLock) {
            currentScenario = normalized
        }
        return normalized.copyPayload()
    }

    fun startCurrentScenario(): ScenarioActionResponse {
        val scenario = synchronized(stateLock) { currentScenario.copyPayload() }
        configureDownstreamScenario(scenario)

        val generatorStartResponse = fetchJson(
            "$generatorBaseUrl/internal/generator/start",
            HttpMethod.POST,
            null,
            DownstreamStatusPayload::class.java,
        )
        requireStatus(generatorStartResponse.status, setOf("starting", "running"), "generator start")

        synchronized(stateLock) {
            currentStatus = ControlPlaneStatus.RUNNING
        }

        return ScenarioActionResponse(status = ControlPlaneStatus.RUNNING.value, scenarioId = scenario.scenarioId)
    }

    fun stopCurrentScenario(): ScenarioActionResponse {
        val scenarioId = synchronized(stateLock) { currentScenario.scenarioId }
        val generatorStopResponse = fetchJson(
            "$generatorBaseUrl/internal/generator/stop",
            HttpMethod.POST,
            null,
            DownstreamStatusPayload::class.java,
        )
        requireStatus(generatorStopResponse.status, setOf("stopping", "idle"), "generator stop")

        val nextStatus = if (generatorStopResponse.status == ControlPlaneStatus.IDLE.value) {
            ControlPlaneStatus.IDLE
        } else {
            ControlPlaneStatus.STOPPING
        }
        synchronized(stateLock) {
            currentStatus = nextStatus
        }

        return ScenarioActionResponse(status = nextStatus.value, scenarioId = scenarioId)
    }

    fun getRecentResults(): List<DecoderResultPayload> =
        fetchJsonList("$decoderBaseUrl/internal/decoder/results/recent")

    private fun configureDownstreamScenario(scenario: ScenarioConfigPayload) {
        val decoderConfigResponse = fetchJson(
            "$decoderBaseUrl/internal/decoder/config",
            HttpMethod.PUT,
            scenario.toDecoderConfigRequest(),
            DownstreamStatusPayload::class.java,
        )
        requireStatus(decoderConfigResponse.status, setOf("configured"), "decoder configuration")

        val generatorConfigResponse = fetchJson(
            "$generatorBaseUrl/internal/generator/config",
            HttpMethod.PUT,
            scenario.toGeneratorConfigRequest(decoderBaseUrl),
            DownstreamStatusPayload::class.java,
        )
        requireStatus(generatorConfigResponse.status, setOf("configured"), "generator configuration")
    }

    private fun validateScenario(payload: ScenarioConfigPayload) {
        if (payload.scenarioId.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "scenarioId must not be blank")
        }
        if (payload.targetJobsPerSecond <= 0.0) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "targetJobsPerSecond must be greater than 0")
        }
        val numberRange = payload.numberRange
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "numberRange must be provided")
        if (numberRange.min > numberRange.max) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "numberRange.min must be less than or equal to numberRange.max")
        }
        if (payload.imageWidthPx < 32) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "imageWidthPx must be at least 32")
        }
        if (payload.imageHeightPx < 16) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "imageHeightPx must be at least 16")
        }
        if (payload.maxInFlight < 1) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "maxInFlight must be at least 1")
        }
        if (payload.workerQueueCapacity < 1) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "workerQueueCapacity must be at least 1")
        }
        if (payload.workerParallelism < 1) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "workerParallelism must be at least 1")
        }
        if (payload.baseDecodeLatencyMs < 1) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "baseDecodeLatencyMs must be at least 1")
        }
        if (payload.latencyJitterMs < 0) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "latencyJitterMs must be at least 0")
        }
    }

    private fun requireStatus(status: String, allowed: Set<String>, operation: String) {
        if (status !in allowed) {
            throw ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "Unexpected status '$status' returned by $operation",
            )
        }
    }

    private fun <T> fetchJson(url: String, method: HttpMethod, body: Any?, responseType: Class<T>): T {
        val request = if (body == null) {
            HttpEntity.EMPTY
        } else {
            HttpEntity(body, jsonHeaders())
        }

        return try {
            val response = restTemplate.exchange(url, method, request, responseType)
            response.body
                ?: throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Downstream call to $url returned an empty body")
        } catch (ex: ResponseStatusException) {
            throw ex
        } catch (ex: RestClientException) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Downstream call to $url failed", ex)
        }
    }

    private fun fetchJsonList(url: String): List<DecoderResultPayload> =
        try {
            val response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                HttpEntity.EMPTY,
                object : ParameterizedTypeReference<List<DecoderResultPayload>>() {},
            )
            response.body?.map { it.copyPayload() }
                ?: throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Downstream call to $url returned an empty body")
        } catch (ex: ResponseStatusException) {
            throw ex
        } catch (ex: RestClientException) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Downstream call to $url failed", ex)
        }

    private fun jsonHeaders(): HttpHeaders =
        HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            accept = listOf(MediaType.APPLICATION_JSON)
        }
}
