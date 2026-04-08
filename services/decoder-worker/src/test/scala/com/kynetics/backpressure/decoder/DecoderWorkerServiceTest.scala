package com.kynetics.backpressure.decoder

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Instant

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.{AfterEach, BeforeEach, Test}

import upickle.default.read

final class DecoderWorkerServiceTest {
  private val client = HttpClient.newHttpClient()
  private var service: DecoderWorkerService = _
  private var baseUri: String = _

  @BeforeEach
  def startService(): Unit = {
    val initialConfig = DecoderConfig(
      scenarioId = "test-scenario",
      workerQueueCapacity = 4,
      workerParallelism = 2,
      baseDecodeLatencyMs = 25,
      latencyJitterMs = 0
    )
    service = DecoderWorkerService.start(0, initialConfig, recentResultsLimit = 8)
    baseUri = s"http://127.0.0.1:${service.port}"
  }

  @AfterEach
  def stopService(): Unit = {
    if (service != null) {
      service.close()
      service = null
    }
  }

  @Test
  def putConfigAppliesSettings(): Unit = {
    val config = DecoderConfig(
      scenarioId = "scenario-phase-1b",
      workerQueueCapacity = 2,
      workerParallelism = 1,
      baseDecodeLatencyMs = 120,
      latencyJitterMs = 10
    )

    val response = sendJson("PUT", "/internal/decoder/config", JsonSupport.encode(config))

    assertEquals(200, response.statusCode())
    val applied = read[ConfigApplied](response.body())
    assertEquals("scenario-phase-1b", applied.scenarioId)
    assertEquals("configured", applied.status)
  }

  @Test
  def postWorkloadsProcessesAndStoresRecentResults(): Unit = {
    val job = sampleJob(jobId = "job-accepted", scenarioId = "test-scenario", expectedNumber = 42, sequence = 1)

    val acceptedResponse = sendJson("POST", "/internal/decoder/workloads", JsonSupport.encode(job))

    assertEquals(202, acceptedResponse.statusCode())
    val accepted = read[AdmissionAccepted](acceptedResponse.body())
    assertEquals(job.jobId, accepted.jobId)
    assertEquals("accepted", accepted.admission)
    assertTrue(accepted.queueDepth >= 0)

    val result = awaitRecentResult(job.jobId)
    assertEquals(job.scenarioId, result.scenarioId)
    assertEquals(job.expectedNumber, result.expectedNumber)
    assertTrue(Set("decoded", "failed").contains(result.status))
    assertTrue(result.confidence >= 0.0 && result.confidence <= 1.0)
    assertTrue(result.processing.decodeLatencyMs >= 25)
    assertFalse(result.processing.workerInstanceId.isBlank)
    assertFalse(result.processing.completedAt.isBlank)

    val metricsResponse = sendJson("GET", "/internal/decoder/metrics")
    assertEquals(200, metricsResponse.statusCode())
    val metrics = read[MetricsSnapshot](metricsResponse.body())
    assertEquals("decoder-worker", metrics.service)
    assertTrue(metrics.acceptedRate > 0.0)
    assertTrue(metrics.completedRate > 0.0)
    assertTrue(metrics.rejectedCount >= 0)
  }

  @Test
  def postWorkloadsRejectsWhenQueueIsSaturated(): Unit = {
    val config = DecoderConfig(
      scenarioId = "saturation-test",
      workerQueueCapacity = 1,
      workerParallelism = 1,
      baseDecodeLatencyMs = 800,
      latencyJitterMs = 0
    )
    val configResponse = sendJson("PUT", "/internal/decoder/config", JsonSupport.encode(config))
    assertEquals(200, configResponse.statusCode())

    val first = sendJson("POST", "/internal/decoder/workloads", JsonSupport.encode(sampleJob("job-1", "saturation-test", 100, 1)))
    val second = sendJson("POST", "/internal/decoder/workloads", JsonSupport.encode(sampleJob("job-2", "saturation-test", 200, 2)))
    val third = sendJson("POST", "/internal/decoder/workloads", JsonSupport.encode(sampleJob("job-3", "saturation-test", 300, 3)))

    assertEquals(202, first.statusCode())
    assertEquals(202, second.statusCode())
    assertEquals(429, third.statusCode())

    val rejection = read[AdmissionRejected](third.body())
    assertEquals("saturated", rejection.status)
    assertTrue(rejection.queueDepth >= 1)
    assertTrue(rejection.reason.contains("queue") || rejection.reason.contains("saturated"))

    val metricsResponse = sendJson("GET", "/internal/decoder/metrics")
    val metrics = read[MetricsSnapshot](metricsResponse.body())
    assertTrue(metrics.queueDepth >= 1)
    assertTrue(metrics.rejectedCount >= 1)
    assertEquals("saturated", metrics.saturationState)
  }

  private def sendJson(method: String, path: String, body: String = ""): HttpResponse[String] = {
    val builder = HttpRequest.newBuilder(URI.create(s"$baseUri$path"))
      .header("Content-Type", "application/json")

    val request = method match {
      case "GET" => builder.GET().build()
      case other => builder.method(other, HttpRequest.BodyPublishers.ofString(body)).build()
    }

    client.send(request, HttpResponse.BodyHandlers.ofString())
  }

  private def awaitRecentResult(jobId: String): DecoderResult = {
    var attempt = 0
    while (attempt < 40) {
      val response = sendJson("GET", "/internal/decoder/results/recent")
      assertEquals(200, response.statusCode())
      val results = read[Vector[DecoderResult]](response.body())
      results.find(_.jobId == jobId) match {
        case Some(result) => return result
        case None =>
          Thread.sleep(50)
          attempt += 1
      }
    }
    fail(s"Timed out waiting for result for $jobId")
  }

  private def sampleJob(jobId: String, scenarioId: String, expectedNumber: Int, sequence: Int): WorkloadJob =
    WorkloadJob(
      jobId = jobId,
      scenarioId = scenarioId,
      sequence = sequence,
      expectedNumber = expectedNumber,
      imagePngBase64 =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/w8AAn8B9t7VDsQAAAAASUVORK5CYII=",
      createdAt = Instant.now().toString
    )
}
