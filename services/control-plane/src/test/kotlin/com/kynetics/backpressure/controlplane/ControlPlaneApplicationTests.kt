package com.kynetics.backpressure.controlplane

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestTemplate

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "backpressure.generator.base-url=http://generator.test",
        "backpressure.decoder.base-url=http://decoder.test",
    ],
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ControlPlaneApplicationTests(
    @Autowired private val restTemplate: TestRestTemplate,
    @Autowired private val controlPlaneRestTemplate: RestTemplate,
) {
    private lateinit var mockServer: MockRestServiceServer

    @BeforeEach
    fun setUp() {
        mockServer = MockRestServiceServer.bindTo(controlPlaneRestTemplate).ignoreExpectOrder(true).build()
    }

    @Test
    fun `actuator health is exposed`() {
        val response = restTemplate.getForEntity("/actuator/health", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `overview returns default scenario and aggregated downstream data`() {
        mockServer.expect(requestTo("http://generator.test/internal/generator/metrics"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(generatorMetricsJson(), MediaType.APPLICATION_JSON))
        mockServer.expect(requestTo("http://decoder.test/internal/decoder/metrics"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(workerMetricsJson(), MediaType.APPLICATION_JSON))
        mockServer.expect(requestTo("http://decoder.test/internal/decoder/results/recent"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(recentResultsJson(), MediaType.APPLICATION_JSON))

        val response = restTemplate.getForEntity("/api/v1/overview", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("\"scenarioId\":\"happy-path-demo\"")
        assertThat(response.body).contains("\"service\":\"workload-generator\"")
        assertThat(response.body).contains("\"service\":\"decoder-worker\"")
        assertThat(response.body).contains("\"status\":\"idle\"")
        assertThat(response.body).contains("\"jobId\":\"job-1\"")
        mockServer.verify()
    }

    @Test
    fun `scenario can be updated and start configures downstream services`() {
        val request = HttpEntity(validScenarioJson("phase-1b-demo"), jsonHeaders())
        val putResponse = restTemplate.exchange(
            "/api/v1/scenario/current",
            HttpMethod.PUT,
            request,
            String::class.java,
        )
        assertThat(putResponse.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(putResponse.body).contains("\"scenarioId\":\"phase-1b-demo\"")

        mockServer.expect(requestTo("http://decoder.test/internal/decoder/config"))
            .andExpect(method(HttpMethod.PUT))
            .andRespond(withSuccess("{\"scenarioId\":\"phase-1b-demo\",\"status\":\"configured\"}", MediaType.APPLICATION_JSON))
        mockServer.expect(requestTo("http://generator.test/internal/generator/config"))
            .andExpect(method(HttpMethod.PUT))
            .andRespond(withSuccess("{\"scenarioId\":\"phase-1b-demo\",\"status\":\"configured\"}", MediaType.APPLICATION_JSON))
        mockServer.expect(requestTo("http://generator.test/internal/generator/start"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("{\"status\":\"running\"}", MediaType.APPLICATION_JSON))

        val startResponse = restTemplate.postForEntity("/api/v1/scenario/current/start", null, String::class.java)

        assertThat(startResponse.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        assertThat(startResponse.body).contains("\"status\":\"running\"")
        assertThat(startResponse.body).contains("\"scenarioId\":\"phase-1b-demo\"")
        mockServer.verify()
    }

    @Test
    fun `updating scenario while running reapplies downstream services immediately`() {
        mockServer.expect(requestTo("http://decoder.test/internal/decoder/config"))
            .andExpect(method(HttpMethod.PUT))
            .andRespond(withSuccess("{\"scenarioId\":\"happy-path-demo\",\"status\":\"configured\"}", MediaType.APPLICATION_JSON))
        mockServer.expect(requestTo("http://generator.test/internal/generator/config"))
            .andExpect(method(HttpMethod.PUT))
            .andRespond(withSuccess("{\"scenarioId\":\"happy-path-demo\",\"status\":\"configured\"}", MediaType.APPLICATION_JSON))
        mockServer.expect(requestTo("http://generator.test/internal/generator/start"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("{\"status\":\"running\"}", MediaType.APPLICATION_JSON))

        val startResponse = restTemplate.postForEntity("/api/v1/scenario/current/start", null, String::class.java)

        assertThat(startResponse.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        mockServer.verify()

        mockServer = MockRestServiceServer.bindTo(controlPlaneRestTemplate).ignoreExpectOrder(true).build()
        mockServer.expect(requestTo("http://decoder.test/internal/decoder/config"))
            .andExpect(method(HttpMethod.PUT))
            .andRespond(withSuccess("{\"scenarioId\":\"rebalanced-demo\",\"status\":\"configured\"}", MediaType.APPLICATION_JSON))
        mockServer.expect(requestTo("http://generator.test/internal/generator/config"))
            .andExpect(method(HttpMethod.PUT))
            .andRespond(withSuccess("{\"scenarioId\":\"rebalanced-demo\",\"status\":\"configured\"}", MediaType.APPLICATION_JSON))

        val updateRequest = HttpEntity(validScenarioJson("rebalanced-demo"), jsonHeaders())
        val updateResponse = restTemplate.exchange(
            "/api/v1/scenario/current",
            HttpMethod.PUT,
            updateRequest,
            String::class.java,
        )

        assertThat(updateResponse.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(updateResponse.body).contains("\"scenarioId\":\"rebalanced-demo\"")
        mockServer.verify()
    }

    @Test
    fun `stop propagates to generator and results endpoint proxies decoder output`() {
        mockServer.expect(requestTo("http://generator.test/internal/generator/stop"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("{\"status\":\"stopping\"}", MediaType.APPLICATION_JSON))

        val stopResponse = restTemplate.postForEntity("/api/v1/scenario/current/stop", null, String::class.java)

        assertThat(stopResponse.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        assertThat(stopResponse.body).contains("\"status\":\"stopping\"")
        assertThat(stopResponse.body).contains("\"scenarioId\":\"happy-path-demo\"")
        mockServer.verify()

        mockServer = MockRestServiceServer.bindTo(controlPlaneRestTemplate).ignoreExpectOrder(true).build()
        mockServer.expect(requestTo("http://decoder.test/internal/decoder/results/recent"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(recentResultsJson(), MediaType.APPLICATION_JSON))

        val resultsResponse = restTemplate.getForEntity("/api/v1/results/recent", String::class.java)

        assertThat(resultsResponse.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(resultsResponse.body).contains("\"jobId\":\"job-1\"")
        assertThat(resultsResponse.body).contains("\"status\":\"decoded\"")
        mockServer.verify()
    }

    @Test
    fun `overview settles to idle after stop when decoder drain is complete`() {
        mockServer.expect(requestTo("http://generator.test/internal/generator/stop"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("{\"status\":\"stopping\"}", MediaType.APPLICATION_JSON))

        val stopResponse = restTemplate.postForEntity("/api/v1/scenario/current/stop", null, String::class.java)

        assertThat(stopResponse.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        assertThat(stopResponse.body).contains("\"status\":\"stopping\"")
        mockServer.verify()

        mockServer = MockRestServiceServer.bindTo(controlPlaneRestTemplate).ignoreExpectOrder(true).build()
        mockServer.expect(requestTo("http://generator.test/internal/generator/metrics"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(
                withSuccess(
                    generatorMetricsJson(
                        acceptedRate = 0.0,
                        completedRate = 0.0,
                        inFlight = 0,
                        queueDepth = 1,
                        rejectedCount = 22,
                        saturationState = "idle",
                    ),
                    MediaType.APPLICATION_JSON,
                ),
            )
        mockServer.expect(requestTo("http://decoder.test/internal/decoder/metrics"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(
                withSuccess(
                    workerMetricsJson(
                        acceptedRate = 0.0,
                        completedRate = 0.0,
                        inFlight = 0,
                        queueDepth = 0,
                        rejectedCount = 22,
                        saturationState = "idle",
                    ),
                    MediaType.APPLICATION_JSON,
                ),
            )
        mockServer.expect(requestTo("http://decoder.test/internal/decoder/results/recent"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON))

        val overviewResponse = restTemplate.getForEntity("/api/v1/overview", String::class.java)

        assertThat(overviewResponse.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(overviewResponse.body).contains("\"status\":\"idle\"")
        mockServer.verify()
    }

    @Test
    fun `overview remains stopping while decoder still drains after stop`() {
        mockServer.expect(requestTo("http://generator.test/internal/generator/stop"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("{\"status\":\"stopping\"}", MediaType.APPLICATION_JSON))

        val stopResponse = restTemplate.postForEntity("/api/v1/scenario/current/stop", null, String::class.java)

        assertThat(stopResponse.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        assertThat(stopResponse.body).contains("\"status\":\"stopping\"")
        mockServer.verify()

        mockServer = MockRestServiceServer.bindTo(controlPlaneRestTemplate).ignoreExpectOrder(true).build()
        mockServer.expect(requestTo("http://generator.test/internal/generator/metrics"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(
                withSuccess(
                    generatorMetricsJson(
                        acceptedRate = 0.0,
                        completedRate = 0.0,
                        inFlight = 0,
                        queueDepth = 1,
                        rejectedCount = 22,
                        saturationState = "idle",
                    ),
                    MediaType.APPLICATION_JSON,
                ),
            )
        mockServer.expect(requestTo("http://decoder.test/internal/decoder/metrics"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(
                withSuccess(
                    workerMetricsJson(
                        acceptedRate = 0.0,
                        completedRate = 0.0,
                        inFlight = 1,
                        queueDepth = 0,
                        rejectedCount = 22,
                        saturationState = "warming-up",
                    ),
                    MediaType.APPLICATION_JSON,
                ),
            )
        mockServer.expect(requestTo("http://decoder.test/internal/decoder/results/recent"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON))

        val overviewResponse = restTemplate.getForEntity("/api/v1/overview", String::class.java)

        assertThat(overviewResponse.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(overviewResponse.body).contains("\"status\":\"stopping\"")
        mockServer.verify()
    }

    @Test
    fun `invalid scenario payload returns bad request`() {
        val request = HttpEntity(
            validScenarioJson("broken-scenario").replace("\"targetJobsPerSecond\": 5.5", "\"targetJobsPerSecond\": 0"),
            jsonHeaders(),
        )

        val response = restTemplate.exchange(
            "/api/v1/scenario/current",
            HttpMethod.PUT,
            request,
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(response.body).contains("targetJobsPerSecond")
    }

    private fun jsonHeaders(): HttpHeaders =
        HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            accept = listOf(MediaType.APPLICATION_JSON)
        }

    private fun validScenarioJson(scenarioId: String): String =
        """
        {
          "scenarioId": "$scenarioId",
          "targetJobsPerSecond": 5.5,
          "numberRange": {
            "min": 10,
            "max": 99
          },
          "imageWidthPx": 96,
          "imageHeightPx": 48,
          "maxInFlight": 8,
          "workerQueueCapacity": 16,
          "workerParallelism": 3,
          "baseDecodeLatencyMs": 120,
          "latencyJitterMs": 25
        }
        """.trimIndent()

    private fun generatorMetricsJson(
        acceptedRate: Double = 5.5,
        completedRate: Double = 5.1,
        inFlight: Int = 4,
        queueDepth: Int = 2,
        rejectedCount: Int = 1,
        saturationState: String = "stable",
    ): String =
        """
        {
          "service": "workload-generator",
          "timestamp": "2026-01-01T00:00:00Z",
          "requestedRate": 6.0,
          "acceptedRate": $acceptedRate,
          "completedRate": $completedRate,
          "inFlight": $inFlight,
          "queueDepth": $queueDepth,
          "p95LatencyMs": 180.0,
          "rejectedCount": $rejectedCount,
          "saturationState": "$saturationState"
        }
        """.trimIndent()

    private fun workerMetricsJson(
        acceptedRate: Double = 5.2,
        completedRate: Double = 4.8,
        inFlight: Int = 3,
        queueDepth: Int = 1,
        rejectedCount: Int = 0,
        saturationState: String = "stable",
    ): String =
        """
        {
          "service": "decoder-worker",
          "timestamp": "2026-01-01T00:00:01Z",
          "requestedRate": 5.5,
          "acceptedRate": $acceptedRate,
          "completedRate": $completedRate,
          "inFlight": $inFlight,
          "queueDepth": $queueDepth,
          "p95LatencyMs": 210.0,
          "rejectedCount": $rejectedCount,
          "saturationState": "$saturationState"
        }
        """.trimIndent()

    private fun recentResultsJson(): String =
        """
        [
          {
            "jobId": "job-1",
            "scenarioId": "happy-path-demo",
            "expectedNumber": 42,
            "decodedNumber": 42,
            "confidence": 0.98,
            "status": "decoded",
            "processing": {
              "enqueueWaitMs": 4,
              "decodeLatencyMs": 91,
              "totalLatencyMs": 95,
              "workerInstanceId": "decoder-1",
              "completedAt": "2026-01-01T00:00:02Z"
            }
          }
        ]
        """.trimIndent()
}
