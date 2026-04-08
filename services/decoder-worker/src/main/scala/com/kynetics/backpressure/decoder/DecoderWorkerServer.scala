package com.kynetics.backpressure.decoder

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.{ExecutorService, Executors}
import scala.util.control.NonFatal
import upickle.default.{Reader, Writer}

final class DecoderWorkerService private (
    private val runtime: DecoderRuntime,
    private val server: HttpServer,
    private val executor: ExecutorService
) extends AutoCloseable {
  def port: Int = server.getAddress.getPort

  override def close(): Unit = {
    server.stop(0)
    executor.shutdownNow()
    runtime.close()
  }
}

object DecoderWorkerService {
  def start(port: Int, initialConfig: DecoderConfig, recentResultsLimit: Int): DecoderWorkerService = {
    val runtime = new DecoderRuntime(initialConfig, recentResultsLimit)
    val executor = Executors.newFixedThreadPool(8)
    val server = HttpServer.create(new InetSocketAddress(port), 0)
    server.setExecutor(executor)

    server.createContext(
      "/internal/decoder/config",
      methodHandler("PUT") { exchange =>
        readJson[DecoderConfig](exchange) match {
          case Left(reason) => sendJson(exchange, 400, ApiError("bad_request", reason))
          case Right(config) => sendJson(exchange, 200, runtime.applyConfig(config))
        }
      }
    )

    server.createContext(
      "/internal/decoder/workloads",
      methodHandler("POST") { exchange =>
        readJson[WorkloadJob](exchange) match {
          case Left(reason) => sendJson(exchange, 400, ApiError("bad_request", reason))
          case Right(job) =>
            runtime.admit(job) match {
              case Right(accepted) => sendJson(exchange, 202, accepted)
              case Left(rejected) => sendJson(exchange, 429, rejected)
            }
        }
      }
    )

    server.createContext(
      "/internal/decoder/metrics",
      methodHandler("GET") { exchange =>
        sendJson(exchange, 200, runtime.metricsSnapshot())
      }
    )

    server.createContext(
      "/internal/decoder/results/recent",
      methodHandler("GET") { exchange =>
        sendJson(exchange, 200, runtime.recentResultsSnapshot())
      }
    )

    server.start()
    new DecoderWorkerService(runtime, server, executor)
  }

  private def methodHandler(expectedMethod: String)(delegate: HttpExchange => Unit): HttpHandler = new HttpHandler {
    override def handle(exchange: HttpExchange): Unit = {
      try {
        if (!exchange.getRequestMethod.equalsIgnoreCase(expectedMethod)) {
          exchange.getResponseHeaders.add("Allow", expectedMethod)
          sendJson(exchange, 405, ApiError("method_not_allowed", s"Expected $expectedMethod"))
        } else {
          delegate(exchange)
        }
      } catch {
        case illegalArgument: IllegalArgumentException =>
          sendJson(exchange, 400, ApiError("bad_request", Option(illegalArgument.getMessage).getOrElse("invalid request")))
        case NonFatal(throwable) =>
          sendJson(exchange, 500, ApiError("internal_error", Option(throwable.getMessage).getOrElse("unexpected error")))
      } finally {
        exchange.close()
      }
    }
  }

  private def readJson[A: Reader](exchange: HttpExchange): Either[String, A] = {
    val body = new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
    JsonSupport.decode[A](body)
  }

  private def sendJson[A: Writer](exchange: HttpExchange, status: Int, value: A): Unit = {
    val responseBody = JsonSupport.encode(value)
    val bytes = responseBody.getBytes(StandardCharsets.UTF_8)
    val headers = exchange.getResponseHeaders
    headers.set("Content-Type", "application/json; charset=utf-8")
    exchange.sendResponseHeaders(status, bytes.length.toLong)
    val output = exchange.getResponseBody
    try {
      output.write(bytes)
    } finally {
      output.close()
    }
  }
}
