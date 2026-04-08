package com.kynetics.backpressure.generator

import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import upickle.default.write

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.{Duration, Instant}
import java.util.concurrent.ThreadLocalRandom
import scala.concurrent.duration.*
import scala.jdk.FutureConverters.*
import scala.util.{Failure, Success, Try}

object GeneratorActor:
  sealed trait Command

  final case class ApplyConfig(config: GeneratorConfig, replyTo: ActorRef[Either[HttpError, ConfigureResponse]]) extends Command
  final case class Start(replyTo: ActorRef[Either[HttpError, StartStopResponse]]) extends Command
  final case class Stop(replyTo: ActorRef[StartStopResponse]) extends Command
  final case class GetMetrics(replyTo: ActorRef[MetricsSnapshot]) extends Command

  private case object GenerateTick extends Command
  private final case class DeliveryCompleted(result: DeliveryResult) extends Command

  private sealed trait DeliveryResult:
    def latencyMs: Long
    def queueDepth: Int

  private final case class DeliveryAccepted(latencyMs: Long, queueDepth: Int) extends DeliveryResult
  private final case class DeliveryRejected(latencyMs: Long, queueDepth: Int) extends DeliveryResult
  private final case class DeliveryFailed(latencyMs: Long, message: String, queueDepth: Int = 0) extends DeliveryResult

  private final case class State(
      config: Option[GeneratorConfig] = None,
      running: Boolean = false,
      nextSequence: Long = 0L,
      inFlight: Int = 0,
      queueDepth: Int = 0,
      effectiveRate: Double = 0.0,
      generationBudget: Double = 0.0,
      rejectedCount: Int = 0,
      recentAcceptedAtMs: Vector[Long] = Vector.empty,
      recentCompletedAtMs: Vector[Long] = Vector.empty,
      recentRejectedAtMs: Vector[Long] = Vector.empty,
      recentLatenciesMs: Vector[Long] = Vector.empty,
      startedAtMs: Option[Long] = None
  )

  private val MetricsWindowMs = 5000L
  private val MaxLatencySamples = 256
  private val TickInterval = 100.millis
  private val TickTimerKey = "generator-tick"
  private val MinBackoffRate = 0.5

  def apply(httpClient: HttpClient): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        behavior(State(), context, timers, httpClient)
      }
    }

  private def behavior(
      state: State,
      context: ActorContext[Command],
      timers: TimerScheduler[Command],
      httpClient: HttpClient
  ): Behavior[Command] =
    Behaviors.receiveMessage {
      case ApplyConfig(config, replyTo) =>
        validateConfig(config) match
          case Some(message) =>
            replyTo ! Left(HttpError(400, message))
            Behaviors.same
          case None =>
            val normalizedConfig = config.copy(decoderBaseUrl = normalizeBaseUrl(config.decoderBaseUrl))
            replyTo ! Right(ConfigureResponse(normalizedConfig.scenarioId, "configured"))
            behavior(
              state.copy(
                config = Some(normalizedConfig),
                effectiveRate = if state.running then normalizedConfig.targetJobsPerSecond else 0.0,
                generationBudget = if state.running then 0.0 else state.generationBudget
              ),
              context,
              timers,
              httpClient
            )

      case Start(replyTo) =>
        state.config match
          case None =>
            replyTo ! Left(HttpError(409, "generator must be configured before it can start"))
            Behaviors.same
          case Some(config) if state.running =>
            replyTo ! Right(StartStopResponse("running"))
            Behaviors.same
          case Some(config) =>
            timers.startTimerWithFixedDelay(TickTimerKey, GenerateTick, TickInterval)
            replyTo ! Right(StartStopResponse("starting"))
            behavior(
              state.copy(
                running = true,
                effectiveRate = config.targetJobsPerSecond,
                generationBudget = 0.0,
                startedAtMs = Some(nowMs())
              ),
              context,
              timers,
              httpClient
            )

      case Stop(replyTo) =>
        timers.cancel(TickTimerKey)
        replyTo ! StartStopResponse(if state.running then "stopping" else "idle")
        behavior(
          state.copy(
            running = false,
            effectiveRate = 0.0,
            generationBudget = 0.0,
            startedAtMs = None
          ),
          context,
          timers,
          httpClient
        )

      case GetMetrics(replyTo) =>
        replyTo ! toMetrics(state, nowMs())
        Behaviors.same

      case GenerateTick =>
        behavior(generateIfPossible(state, context, httpClient), context, timers, httpClient)

      case DeliveryCompleted(result) =>
        behavior(onDeliveryCompleted(state, result), context, timers, httpClient)
    }

  private def generateIfPossible(
      state: State,
      context: ActorContext[Command],
      httpClient: HttpClient
  ): State =
    state.config match
      case None => state
      case Some(config) if !state.running => state
      case Some(config) =>
        val replenishedBudget =
          math.min(config.maxInFlight.toDouble, state.generationBudget + (state.effectiveRate * TickInterval.toMillis / 1000.0))
        val availableSlots = math.max(0, config.maxInFlight - state.inFlight)
        val jobsToDispatch = math.min(availableSlots, replenishedBudget.toInt)

        if jobsToDispatch <= 0 then state.copy(generationBudget = replenishedBudget)
        else
          var updated = state.copy(generationBudget = replenishedBudget - jobsToDispatch.toDouble)
          var remaining = jobsToDispatch
          while remaining > 0 do
            updated = dispatchOne(updated, config, context, httpClient)
            remaining -= 1
          updated

  private def dispatchOne(
      state: State,
      config: GeneratorConfig,
      context: ActorContext[Command],
      httpClient: HttpClient
  ): State =
    val nextNumber = ThreadLocalRandom.current.nextInt(config.numberRange.min, config.numberRange.max + 1)
    val job = WorkloadJob(
      jobId = s"${config.scenarioId}-${state.nextSequence}",
      scenarioId = config.scenarioId,
      sequence = state.nextSequence,
      expectedNumber = nextNumber,
      imagePngBase64 = ImageRenderer.renderPngBase64(nextNumber, config.imageWidthPx, config.imageHeightPx),
      createdAt = Instant.now().toString
    )

    val request = HttpRequest.newBuilder()
      .uri(URI.create(s"${config.decoderBaseUrl}/internal/decoder/workloads"))
      .header("Content-Type", "application/json")
      .timeout(Duration.ofSeconds(5))
      .POST(HttpRequest.BodyPublishers.ofString(write(job)))
      .build()

    val startedAtNanos = System.nanoTime()
    val responseFuture =
      httpClient
        .sendAsync(request, HttpResponse.BodyHandlers.ofString())
        .asScala

    context.pipeToSelf(responseFuture) {
      case Success(response) => DeliveryCompleted(toDeliveryResult(response, startedAtNanos))
      case Failure(error) =>
        DeliveryCompleted(
          DeliveryFailed(
            latencyMs = elapsedMs(startedAtNanos),
            message = Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)
          )
        )
    }

    state.copy(nextSequence = state.nextSequence + 1, inFlight = state.inFlight + 1)

  private def onDeliveryCompleted(state: State, result: DeliveryResult): State =
    val now = nowMs()
    val completedAt = pruneWindow(state.recentCompletedAtMs :+ now, now)
    val rejectedAt = pruneWindow(state.recentRejectedAtMs, now)
    val acceptedAt = pruneWindow(state.recentAcceptedAtMs, now)
    val latencies = (state.recentLatenciesMs :+ math.max(result.latencyMs, 0L)).takeRight(MaxLatencySamples)

    val baseState = state.copy(
      inFlight = math.max(0, state.inFlight - 1),
      queueDepth = math.max(0, result.queueDepth),
      recentCompletedAtMs = completedAt,
      recentRejectedAtMs = rejectedAt,
      recentAcceptedAtMs = acceptedAt,
      recentLatenciesMs = latencies
    )

    result match
      case _: DeliveryAccepted =>
        baseState.copy(
          recentAcceptedAtMs = pruneWindow(baseState.recentAcceptedAtMs :+ now, now),
          effectiveRate = recoverRate(baseState.config, baseState.effectiveRate)
        )
      case _: DeliveryRejected =>
        baseState.copy(
          rejectedCount = baseState.rejectedCount + 1,
          recentRejectedAtMs = pruneWindow(baseState.recentRejectedAtMs :+ now, now),
          effectiveRate = backoffRate(baseState.config, baseState.effectiveRate)
        )
      case _: DeliveryFailed =>
        baseState

  private def toMetrics(state: State, now: Long): MetricsSnapshot =
    val acceptedAt = pruneWindow(state.recentAcceptedAtMs, now)
    val completedAt = pruneWindow(state.recentCompletedAtMs, now)
    val rejectedAt = pruneWindow(state.recentRejectedAtMs, now)

    MetricsSnapshot(
      service = "workload-generator",
      timestamp = Instant.ofEpochMilli(now).toString,
      requestedRate = state.config.map(_.targetJobsPerSecond).getOrElse(0.0),
      acceptedRate = ratePerSecond(acceptedAt.size),
      completedRate = ratePerSecond(completedAt.size),
      inFlight = state.inFlight,
      queueDepth = state.queueDepth,
      p95LatencyMs = percentile95(state.recentLatenciesMs),
      rejectedCount = state.rejectedCount,
      saturationState = saturationState(state.running, state.startedAtMs, rejectedAt, now)
    )

  private def toDeliveryResult(response: HttpResponse[String], startedAtNanos: Long): DeliveryResult =
    val latencyMs = elapsedMs(startedAtNanos)
    val queueDepth = extractQueueDepth(response.body())
    response.statusCode() match
      case 202 => DeliveryAccepted(latencyMs, queueDepth)
      case 429 => DeliveryRejected(latencyMs, queueDepth)
      case status =>
        DeliveryFailed(latencyMs, s"unexpected decoder status $status", queueDepth)

  private def extractQueueDepth(body: String): Int =
    Try(ujson.read(body)("queueDepth").num.toInt).getOrElse(0)

  private def validateConfig(config: GeneratorConfig): Option[String] =
    val decoderUri = Try(URI.create(config.decoderBaseUrl)).toOption
    cond(
      Option(config.scenarioId).exists(_.trim.nonEmpty),
      "scenarioId must not be blank"
    ).orElse(
      cond(config.targetJobsPerSecond > 0, "targetJobsPerSecond must be greater than zero")
    ).orElse(
      cond(config.numberRange.min <= config.numberRange.max, "numberRange.min must be less than or equal to numberRange.max")
    ).orElse(
      cond(config.imageWidthPx >= 32, "imageWidthPx must be at least 32")
    ).orElse(
      cond(config.imageHeightPx >= 16, "imageHeightPx must be at least 16")
    ).orElse(
      cond(config.maxInFlight >= 1, "maxInFlight must be at least 1")
    ).orElse(
      cond(decoderUri.exists(uri => uri.getScheme != null && uri.getHost != null), "decoderBaseUrl must be an absolute URI")
    )

  private def cond(predicate: Boolean, message: String): Option[String] =
    if predicate then None else Some(message)

  private def normalizeBaseUrl(value: String): String =
    value.stripSuffix("/")

  private def pruneWindow(values: Vector[Long], now: Long): Vector[Long] =
    values.filter(_ >= (now - MetricsWindowMs))

  private def ratePerSecond(count: Int): Double =
    count.toDouble * 1000.0 / MetricsWindowMs.toDouble

  private def percentile95(latencies: Vector[Long]): Double =
    if latencies.isEmpty then 0.0
    else
      val sorted = latencies.sorted
      val index = math.ceil(sorted.size.toDouble * 0.95).toInt - 1
      sorted(index.max(0).min(sorted.size - 1)).toDouble

  private def saturationState(
      running: Boolean,
      startedAtMs: Option[Long],
      recentRejectedAtMs: Vector[Long],
      now: Long
  ): String =
    if !running then "idle"
    else if recentRejectedAtMs.nonEmpty then "saturated"
    else if startedAtMs.exists(started => now - started < MetricsWindowMs) then "warming-up"
    else "stable"

  private def recoverRate(config: Option[GeneratorConfig], currentRate: Double): Double =
    config match
      case None => 0.0
      case Some(value) if currentRate >= value.targetJobsPerSecond => value.targetJobsPerSecond
      case Some(value) if currentRate <= 0.0 => math.min(value.targetJobsPerSecond, 1.0)
      case Some(value) =>
        val delta = value.targetJobsPerSecond - currentRate
        math.min(value.targetJobsPerSecond, currentRate + math.max(0.2, delta * 0.15))

  private def backoffRate(config: Option[GeneratorConfig], currentRate: Double): Double =
    config match
      case None => 0.0
      case Some(value) =>
        math.max(MinBackoffRate, math.min(value.targetJobsPerSecond, math.max(MinBackoffRate, currentRate * 0.5)))

  private def nowMs(): Long =
    System.currentTimeMillis()

  private def elapsedMs(startedAtNanos: Long): Long =
    Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis
