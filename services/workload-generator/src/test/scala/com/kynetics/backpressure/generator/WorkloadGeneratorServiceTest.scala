package com.kynetics.backpressure.generator

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import org.junit.jupiter.api.Assertions.{assertArrayEquals, assertEquals, assertTrue, fail}
import org.junit.jupiter.api.Test
import upickle.default.{read, write}

import java.net.{InetSocketAddress, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{CopyOnWriteArrayList, Executors, TimeUnit}
import scala.annotation.tailrec
import scala.jdk.CollectionConverters.*

class WorkloadGeneratorServiceTest:
  private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

  @Test
  def `config endpoint applies scenario and exposes idle metrics`(): Unit =
    val decoder = TestDecoderServer.accepting()
    val service = WorkloadGeneratorService.start(0)

    try
      configure(service, decoder.baseUrl, targetJobsPerSecond = 8.0, maxInFlight = 3)

      val metrics = getMetrics(service)
      assertEquals("workload-generator", metrics.service)
      assertEquals(8.0, metrics.requestedRate, 0.001)
      assertEquals("idle", metrics.saturationState)
      assertEquals(0, metrics.rejectedCount)
      assertEquals(0.0, metrics.acceptedRate, 0.001)
      assertEquals(0.0, metrics.completedRate, 0.001)
    finally
      service.close()
      decoder.close()

  @Test
  def `start and stop submit png workloads to decoder`(): Unit =
    val decoder = TestDecoderServer.accepting()
    val service = WorkloadGeneratorService.start(0)

    try
      configure(service, decoder.baseUrl, targetJobsPerSecond = 20.0, maxInFlight = 4)

      val startResponse = post(service.baseUri.resolve("/internal/generator/start"))
      assertEquals(202, startResponse.statusCode())
      assertEquals("starting", read[StartStopResponse](startResponse.body()).status)

      waitUntil(Duration.ofSeconds(4), "generator to submit workloads") {
        decoder.requestCount >= 4
      }

      val firstJob = decoder.jobsSnapshot.headOption.getOrElse(fail("expected at least one workload job"))
      assertTrue(firstJob.expectedNumber >= 10 && firstJob.expectedNumber <= 99)
      assertEquals("phase1b-test", firstJob.scenarioId)
      assertTrue(firstJob.imagePngBase64.nonEmpty)

      val pngBytes = Base64.getDecoder.decode(firstJob.imagePngBase64)
      assertArrayEquals(
        Array[Byte](137.toByte, 80.toByte, 78.toByte, 71.toByte, 13.toByte, 10.toByte, 26.toByte, 10.toByte),
        pngBytes.take(8)
      )

      val metrics = getMetrics(service)
      assertTrue(metrics.acceptedRate > 0.0, s"expected acceptedRate > 0 but was ${metrics.acceptedRate}")
      assertTrue(metrics.completedRate > 0.0, s"expected completedRate > 0 but was ${metrics.completedRate}")
      assertEquals(0, metrics.rejectedCount)

      val stopResponse = post(service.baseUri.resolve("/internal/generator/stop"))
      assertEquals(202, stopResponse.statusCode())
      assertEquals("stopping", read[StartStopResponse](stopResponse.body()).status)

      Thread.sleep(300)
      val countAfterStop = decoder.requestCount
      Thread.sleep(300)
      assertEquals(countAfterStop, decoder.requestCount, "request count should stabilize after stop")
    finally
      service.close()
      decoder.close()

  @Test
  def `metrics reflect saturated decoder responses`(): Unit =
    val decoder = TestDecoderServer.saturated(queueDepth = 9)
    val service = WorkloadGeneratorService.start(0)

    try
      configure(service, decoder.baseUrl, targetJobsPerSecond = 18.0, maxInFlight = 4)
      post(service.baseUri.resolve("/internal/generator/start"))

      waitUntil(Duration.ofSeconds(4), "generator to observe decoder saturation") {
        getMetrics(service).rejectedCount > 0
      }

      val metrics = getMetrics(service)
      assertEquals("saturated", metrics.saturationState)
      assertTrue(metrics.completedRate > 0.0, s"expected completedRate > 0 but was ${metrics.completedRate}")
      assertTrue(metrics.rejectedCount > 0, s"expected rejectedCount > 0 but was ${metrics.rejectedCount}")
      assertEquals(0.0, metrics.acceptedRate, 0.001)
      assertEquals(9, metrics.queueDepth)
    finally
      service.close()
      decoder.close()

  private def configure(
      service: WorkloadGeneratorService,
      decoderBaseUrl: String,
      targetJobsPerSecond: Double,
      maxInFlight: Int
  ): Unit =
    val response = put(
      service.baseUri.resolve("/internal/generator/config"),
      write(
        GeneratorConfig(
          scenarioId = "phase1b-test",
          targetJobsPerSecond = targetJobsPerSecond,
          numberRange = NumberRange(10, 99),
          imageWidthPx = 96,
          imageHeightPx = 40,
          maxInFlight = maxInFlight,
          decoderBaseUrl = decoderBaseUrl
        )
      )
    )

    assertEquals(200, response.statusCode())
    assertEquals("configured", read[ConfigureResponse](response.body()).status)

  private def getMetrics(service: WorkloadGeneratorService): MetricsSnapshot =
    val response = get(service.baseUri.resolve("/internal/generator/metrics"))
    assertEquals(200, response.statusCode())
    read[MetricsSnapshot](response.body())

  private def put(uri: URI, body: String): HttpResponse[String] =
    sendJson("PUT", uri, body)

  private def post(uri: URI): HttpResponse[String] =
    sendJson("POST", uri, "")

  private def get(uri: URI): HttpResponse[String] =
    client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString())

  private def sendJson(method: String, uri: URI, body: String): HttpResponse[String] =
    val requestBuilder = HttpRequest.newBuilder(uri).header("Content-Type", "application/json")
    val request =
      if method == "PUT" then requestBuilder.PUT(HttpRequest.BodyPublishers.ofString(body)).build()
      else requestBuilder.POST(HttpRequest.BodyPublishers.ofString(body)).build()

    client.send(request, HttpResponse.BodyHandlers.ofString())

  private def waitUntil(deadlineFromNow: Duration, description: String)(predicate: => Boolean): Unit =
    val deadlineAt = System.nanoTime() + deadlineFromNow.toNanos

    @tailrec
    def loop(): Unit =
      if predicate then ()
      else if System.nanoTime() >= deadlineAt then fail(s"Timed out waiting for $description")
      else
        Thread.sleep(50)
        loop()

    loop()

private final class TestDecoderServer private (saturatedResponses: Boolean, queueDepth: Int) extends AutoCloseable:
  private val requestsSeen = new AtomicInteger(0)
  private val jobs = new CopyOnWriteArrayList[WorkloadJob]()
  private val executor = Executors.newCachedThreadPool()
  private val server = HttpServer.create(new InetSocketAddress(0), 0)

  server.createContext(
    "/internal/decoder/workloads",
    new HttpHandler:
      override def handle(exchange: HttpExchange): Unit =
        try
          val payload = new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
          val job = read[WorkloadJob](payload)
          jobs.add(job)
          val count = requestsSeen.incrementAndGet()
          val body =
            if saturatedResponses then
              s"""{"status":"saturated","reason":"test saturation","queueDepth":$queueDepth}"""
            else
              s"""{"jobId":"${job.jobId}","scenarioId":"${job.scenarioId}","admission":"accepted","queueDepth":${count % 3}}"""
          val status = if saturatedResponses then 429 else 202
          val bytes = body.getBytes(StandardCharsets.UTF_8)
          exchange.getResponseHeaders.set("Content-Type", "application/json")
          exchange.sendResponseHeaders(status, bytes.length.toLong)
          exchange.getResponseBody.write(bytes)
        finally exchange.close()
  )

  server.setExecutor(executor)
  server.start()

  def baseUrl: String = s"http://127.0.0.1:${server.getAddress.getPort}"

  def requestCount: Int = requestsSeen.get()

  def jobsSnapshot: Vector[WorkloadJob] = jobs.asScala.toVector

  override def close(): Unit =
    server.stop(0)
    executor.shutdownNow()
    executor.awaitTermination(5, TimeUnit.SECONDS)

object TestDecoderServer:
  def accepting(): TestDecoderServer = new TestDecoderServer(saturatedResponses = false, queueDepth = 0)

  def saturated(queueDepth: Int): TestDecoderServer = new TestDecoderServer(saturatedResponses = true, queueDepth = queueDepth)
