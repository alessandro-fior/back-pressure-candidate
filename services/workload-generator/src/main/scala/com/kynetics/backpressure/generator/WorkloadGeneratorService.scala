package com.kynetics.backpressure.generator

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Scheduler}
import org.apache.pekko.util.Timeout
import upickle.default.{read, write}

import java.net.{InetSocketAddress, URI}
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import java.time.Duration as JavaDuration
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{ExecutorService, Executors, TimeUnit}
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext}
import scala.util.control.NonFatal

final case class BootstrapSettings(port: Int, defaultDecoderBaseUrl: String)

final class WorkloadGeneratorService private (
    bootstrap: BootstrapSettings,
    private val system: ActorSystem[GeneratorActor.Command],
    private val server: HttpServer,
    private val executor: ExecutorService
) extends AutoCloseable:

  private val closed = new AtomicBoolean(false)

  private given Timeout = Timeout(3.seconds)
  private given Scheduler = system.scheduler
  private given ExecutionContext = system.executionContext

  def boundPort: Int = server.getAddress.getPort

  def baseUri: URI = URI.create(s"http://127.0.0.1:$boundPort")

  def awaitShutdown(): Unit =
    Await.ready(system.whenTerminated, scala.concurrent.duration.Duration.Inf)

  override def close(): Unit =
    if closed.compareAndSet(false, true) then
      server.stop(0)
      executor.shutdownNow()
      system.terminate()
      Await.ready(system.whenTerminated, 10.seconds)
      executor.awaitTermination(5, TimeUnit.SECONDS)

  private[generator] def registerContexts(): Unit =
    server.createContext("/internal/generator/config", handler(handleConfig))
    server.createContext("/internal/generator/start", handler(handleStart))
    server.createContext("/internal/generator/stop", handler(handleStop))
    server.createContext("/internal/generator/metrics", handler(handleMetrics))

  private def handler(delegate: HttpExchange => Unit): HttpHandler =
    new HttpHandler:
      override def handle(exchange: HttpExchange): Unit =
        try delegate(exchange)
        catch
          case NonFatal(error) =>
            sendJson(exchange, 500, write(ErrorResponse(s"internal error: ${error.getClass.getSimpleName}")))
        finally exchange.close()

  private def handleConfig(exchange: HttpExchange): Unit =
    if requireMethod(exchange, "PUT") then
      try
        val rawConfig = read[GeneratorConfig](requestBody(exchange))
        val preparedConfig =
          if rawConfig.decoderBaseUrl.trim.nonEmpty then rawConfig
          else rawConfig.copy(decoderBaseUrl = bootstrap.defaultDecoderBaseUrl)

        askActor[Either[HttpError, ConfigureResponse]](replyTo => GeneratorActor.ApplyConfig(preparedConfig, replyTo)) match
          case Right(response) => sendJson(exchange, 200, write(response))
          case Left(error)     => sendJson(exchange, error.statusCode, write(ErrorResponse(error.message)))
      catch
        case NonFatal(error) =>
          sendJson(exchange, 400, write(ErrorResponse(s"invalid config payload: ${error.getMessage}")))

  private def handleStart(exchange: HttpExchange): Unit =
    if requireMethod(exchange, "POST") then
      askActor[Either[HttpError, StartStopResponse]](replyTo => GeneratorActor.Start(replyTo)) match
        case Right(response) => sendJson(exchange, 202, write(response))
        case Left(error)     => sendJson(exchange, error.statusCode, write(ErrorResponse(error.message)))

  private def handleStop(exchange: HttpExchange): Unit =
    if requireMethod(exchange, "POST") then
      val response = askActor[StartStopResponse](replyTo => GeneratorActor.Stop(replyTo))
      sendJson(exchange, 202, write(response))

  private def handleMetrics(exchange: HttpExchange): Unit =
    if requireMethod(exchange, "GET") then
      val metrics = askActor[MetricsSnapshot](replyTo => GeneratorActor.GetMetrics(replyTo))
      sendJson(exchange, 200, write(metrics))

  private def askActor[A](build: ActorRef[A] => GeneratorActor.Command): A =
    Await.result(system.ask(build), 3.seconds)

  private def requireMethod(exchange: HttpExchange, expectedMethod: String): Boolean =
    if exchange.getRequestMethod.equalsIgnoreCase(expectedMethod) then true
    else
      exchange.getResponseHeaders.add("Allow", expectedMethod)
      sendJson(exchange, 405, write(ErrorResponse(s"method ${exchange.getRequestMethod} not allowed")))
      false

  private def requestBody(exchange: HttpExchange): String =
    new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)

  private def sendJson(exchange: HttpExchange, statusCode: Int, body: String): Unit =
    val payload = body.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.set("Content-Type", "application/json")
    exchange.sendResponseHeaders(statusCode, payload.length.toLong)
    val output = exchange.getResponseBody
    output.write(payload)
    output.flush()

object WorkloadGeneratorService:
  def start(port: Int, defaultDecoderBaseUrl: String = "http://decoder-worker:8082"): WorkloadGeneratorService =
    val executor = Executors.newCachedThreadPool()
    val system = ActorSystem(
      GeneratorActor(
        HttpClient
          .newBuilder()
          .connectTimeout(JavaDuration.ofSeconds(2))
          .build()
      ),
      s"workload-generator-${UUID.randomUUID().toString.take(8)}"
    )

    val server = HttpServer.create(new InetSocketAddress(port), 0)
    server.setExecutor(executor)

    val service = new WorkloadGeneratorService(BootstrapSettings(port, defaultDecoderBaseUrl), system, server, executor)
    service.registerContexts()
    server.start()
    service

  def fromReferenceConfig(): WorkloadGeneratorService =
    val config = ConfigFactory.load()
    start(
      port = config.getInt("backpressure.http.port"),
      defaultDecoderBaseUrl = config.getString("backpressure.decoder.base-url")
    )
