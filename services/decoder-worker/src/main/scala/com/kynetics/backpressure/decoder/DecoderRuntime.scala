package com.kynetics.backpressure.decoder

import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}

import org.apache.pekko.Done
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.pattern.after
import org.apache.pekko.stream.{BoundedSourceQueue, Materializer, QueueOfferResult, SystemMaterializer}
import org.apache.pekko.stream.scaladsl.{Keep, Sink, Source}

import scala.collection.mutable
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Try
import scala.util.hashing.MurmurHash3

final class DecoderRuntime(initialConfig: DecoderConfig, recentResultsLimit: Int) extends AutoCloseable {
  private final case class QueuedWorkload(job: WorkloadJob, enqueuedAt: Instant)
  private final case class PipelineState(
      config: DecoderConfig,
      queue: BoundedSourceQueue[QueuedWorkload],
      streamDone: Future[Done]
  )

  private val serviceName = "decoder-worker"
  private val workerInstanceId = s"decoder-worker-${java.util.UUID.randomUUID().toString.take(8)}"
  private val saturationThresholdRatio = 0.8d
  private val metricsWindowMillis = 10000L
  private val latencyHistoryLimit = 256
  private val queueLock = new AnyRef
  private val statsLock = new AnyRef
  private val resultsLock = new AnyRef
  private val queueDepth = new AtomicInteger(0)
  private val inFlight = new AtomicInteger(0)
  private val rejectedCount = new AtomicLong(0L)
  private val requestedTimestamps = mutable.ArrayDeque.empty[Long]
  private val acceptedTimestamps = mutable.ArrayDeque.empty[Long]
  private val completedTimestamps = mutable.ArrayDeque.empty[Long]
  private val recentLatencies = mutable.ArrayDeque.empty[Long]
  private val recentResults = mutable.ArrayDeque.empty[DecoderResult]

  private given ActorSystem = ActorSystem(serviceName)
  private given ExecutionContext = summon[ActorSystem].dispatcher
  private given Materializer = SystemMaterializer(summon[ActorSystem]).materializer

  @volatile private var activeConfig: DecoderConfig = initialConfig
  private var activePipeline: PipelineState = createPipeline(initialConfig)

  def applyConfig(candidate: DecoderConfig): ConfigApplied = {
    val config = validateConfig(candidate).fold(reason => throw new IllegalArgumentException(reason), identity)
    val drainingPipeline = queueLock.synchronized {
      val nextPipeline = createPipeline(config)
      val previousPipeline = activePipeline
      activePipeline = nextPipeline
      activeConfig = config
      previousPipeline
    }
    drainingPipeline.queue.complete()
    awaitDrain(drainingPipeline.streamDone)
    ConfigApplied(config.scenarioId, "configured")
  }

  def admit(job: WorkloadJob): Either[AdmissionRejected, AdmissionAccepted] = {
    val validJob = validateJob(job).fold(reason => throw new IllegalArgumentException(reason), identity)
    recordTimestamp(requestedTimestamps)

    queueLock.synchronized {
      val config = activeConfig
      val currentDepth = queueDepth.get()
      val currentInFlight = inFlight.get()
      if (shouldReject(config, currentDepth, currentInFlight)) {
        registerRejected()
        Left(AdmissionRejected(status = "saturated", reason = saturationReason(config, currentDepth, currentInFlight), queueDepth = currentDepth))
      } else {
        queueDepth.incrementAndGet()
        val offerResult =
          try {
            activePipeline.queue.offer(QueuedWorkload(validJob, Instant.now()))
          } catch {
            case throwable: Throwable => QueueOfferResult.Failure(throwable)
          }

        offerResult match {
          case QueueOfferResult.Enqueued =>
            recordTimestamp(acceptedTimestamps)
            Right(AdmissionAccepted(jobId = validJob.jobId, scenarioId = validJob.scenarioId, admission = "accepted", queueDepth = queueDepth.get()))
          case QueueOfferResult.Dropped =>
            decrementNonNegative(queueDepth)
            registerRejected()
            Left(AdmissionRejected(status = "saturated", reason = "queue full", queueDepth = queueDepth.get()))
          case QueueOfferResult.QueueClosed =>
            decrementNonNegative(queueDepth)
            registerRejected()
            Left(AdmissionRejected(status = "saturated", reason = "queue unavailable", queueDepth = queueDepth.get()))
          case QueueOfferResult.Failure(throwable) =>
            decrementNonNegative(queueDepth)
            registerRejected()
            val message = Option(throwable.getMessage).filter(_.nonEmpty).getOrElse("queue failure")
            Left(AdmissionRejected(status = "saturated", reason = message, queueDepth = queueDepth.get()))
        }
      }
    }
  }

  def metricsSnapshot(): MetricsSnapshot = {
    val now = System.currentTimeMillis()
    val (requestedRate, acceptedRate, completedRate, p95LatencyMs) = statsLock.synchronized {
      (
        rate(requestedTimestamps, now),
        rate(acceptedTimestamps, now),
        rate(completedTimestamps, now),
        percentile95(recentLatencies)
      )
    }
    val depth = queueDepth.get()
    val active = inFlight.get()
    MetricsSnapshot(
      service = serviceName,
      timestamp = Instant.ofEpochMilli(now).toString,
      requestedRate = requestedRate,
      acceptedRate = acceptedRate,
      completedRate = completedRate,
      inFlight = active,
      queueDepth = depth,
      p95LatencyMs = p95LatencyMs,
      rejectedCount = rejectedCount.get(),
      saturationState = saturationState(activeConfig, depth, active)
    )
  }

  def recentResultsSnapshot(): Vector[DecoderResult] =
    resultsLock.synchronized {
      recentResults.toVector
    }

  override def close(): Unit = {
    val currentPipeline = queueLock.synchronized(activePipeline)
    currentPipeline.queue.complete()
    awaitDrain(currentPipeline.streamDone)
    Await.ready(summon[ActorSystem].terminate(), 5.seconds)
  }

  private def createPipeline(config: DecoderConfig): PipelineState = {
    val (queue, streamDone) = Source
      .queue[QueuedWorkload](config.workerQueueCapacity)
      .map { queued =>
        decrementNonNegative(queueDepth)
        inFlight.incrementAndGet()
        queued
      }
      .mapAsync(config.workerParallelism)(processQueued(config, _))
      .toMat(Sink.foreach[DecoderResult](recordCompletion))(Keep.both)
      .run()

    PipelineState(config, queue, streamDone)
  }

  private def processQueued(config: DecoderConfig, queued: QueuedWorkload): Future[DecoderResult] = {
    val enqueueWaitMs = math.max(0L, java.time.Duration.between(queued.enqueuedAt, Instant.now()).toMillis).toInt
    val decodeLatencyMs = decodeLatency(config, queued.job)
    after(decodeLatencyMs.millis, summon[ActorSystem].scheduler) {
      Future.successful(simulateDecode(queued, enqueueWaitMs, decodeLatencyMs))
    }.recover { case throwable =>
      failedResult(queued, enqueueWaitMs, decodeLatencyMs, throwable)
    }.andThen { case _ =>
      decrementNonNegative(inFlight)
    }
  }

  private def simulateDecode(queued: QueuedWorkload, enqueueWaitMs: Int, decodeLatencyMs: Int): DecoderResult = {
    val bytes = Base64.getDecoder.decode(queued.job.imagePngBase64)
    val hashSeed = MurmurHash3.productHash((queued.job.jobId, queued.job.scenarioId, queued.job.sequence, queued.job.expectedNumber))
    val hash = MurmurHash3.mix(hashSeed, MurmurHash3.bytesHash(bytes))
    val outcome = math.floorMod(hash, 20)

    val (status, decodedNumber, confidence) =
      if (outcome == 0) {
        ("failed", Option.empty[Int], 0.12 + (math.floorMod(hash, 7).toDouble / 100.0))
      } else if (outcome <= 3) {
        val offset = (math.floorMod(hash, 3) + 1)
        val direction = if (math.floorMod(hash, 2) == 0) 1 else -1
        val candidate = queued.job.expectedNumber + (offset * direction)
        ("decoded", Some(candidate), 0.46 + (math.floorMod(hash, 18).toDouble / 100.0))
      } else {
        ("decoded", Some(queued.job.expectedNumber), 0.84 + (math.floorMod(hash, 12).toDouble / 100.0))
      }

    buildResult(queued, enqueueWaitMs, decodeLatencyMs, status, decodedNumber, confidence)
  }

  private def failedResult(queued: QueuedWorkload, enqueueWaitMs: Int, decodeLatencyMs: Int, throwable: Throwable): DecoderResult = {
    val fallbackConfidence = if (Option(throwable.getMessage).exists(_.nonEmpty)) 0.09 else 0.05
    buildResult(queued, enqueueWaitMs, decodeLatencyMs, "failed", None, fallbackConfidence)
  }

  private def buildResult(
      queued: QueuedWorkload,
      enqueueWaitMs: Int,
      decodeLatencyMs: Int,
      status: String,
      decodedNumber: Option[Int],
      confidence: Double
  ): DecoderResult = {
    val completedAt = Instant.now()
    val totalLatencyMs = math.max(
      enqueueWaitMs + decodeLatencyMs,
      java.time.Duration.between(queued.enqueuedAt, completedAt).toMillis.toInt
    )
    DecoderResult(
      jobId = queued.job.jobId,
      scenarioId = queued.job.scenarioId,
      expectedNumber = queued.job.expectedNumber,
      decodedNumber = decodedNumber,
      confidence = roundConfidence(confidence),
      status = status,
      processing = ProcessingMetadata(
        enqueueWaitMs = enqueueWaitMs,
        decodeLatencyMs = decodeLatencyMs,
        totalLatencyMs = totalLatencyMs,
        workerInstanceId = workerInstanceId,
        completedAt = completedAt.toString
      )
    )
  }

  private def recordCompletion(result: DecoderResult): Unit = {
    resultsLock.synchronized {
      recentResults.prepend(result)
      while (recentResults.size > recentResultsLimit) {
        recentResults.removeLast()
      }
    }
    statsLock.synchronized {
      recordTimestamp(completedTimestamps)
      recentLatencies.append(result.processing.totalLatencyMs.toLong)
      while (recentLatencies.size > latencyHistoryLimit) {
        recentLatencies.removeHead()
      }
    }
  }

  private def recordTimestamp(buffer: mutable.ArrayDeque[Long]): Unit =
    statsLock.synchronized {
      val now = System.currentTimeMillis()
      buffer.append(now)
      prune(buffer, now)
    }

  private def registerRejected(): Unit = {
    rejectedCount.incrementAndGet()
  }

  private def rate(buffer: mutable.ArrayDeque[Long], now: Long): Double = {
    prune(buffer, now)
    roundToThreeDecimals(buffer.size.toDouble / (metricsWindowMillis.toDouble / 1000.0))
  }

  private def prune(buffer: mutable.ArrayDeque[Long], now: Long): Unit = {
    val cutoff = now - metricsWindowMillis
    while (buffer.headOption.exists(_ < cutoff)) {
      buffer.removeHead()
    }
  }

  private def percentile95(values: mutable.ArrayDeque[Long]): Double = {
    if (values.isEmpty) {
      0.0
    } else {
      val sorted = values.toVector.sorted
      val index = math.max(0, math.ceil(sorted.size * 0.95d).toInt - 1)
      sorted(index).toDouble
    }
  }

  private def shouldReject(config: DecoderConfig, depth: Int, active: Int): Boolean =
    depth >= config.workerQueueCapacity || (depth >= saturationThreshold(config) && active >= config.workerParallelism)

  private def saturationReason(config: DecoderConfig, depth: Int, active: Int): String =
    if (depth >= config.workerQueueCapacity) "queue full" else s"worker saturated (inFlight=$active, threshold=${saturationThreshold(config)})"

  private def saturationState(config: DecoderConfig, depth: Int, active: Int): String = {
    if (depth == 0 && active == 0) {
      "idle"
    } else if (shouldReject(config, depth, active)) {
      "saturated"
    } else if (active < config.workerParallelism && depth == 0) {
      "warming-up"
    } else {
      "stable"
    }
  }

  private def saturationThreshold(config: DecoderConfig): Int =
    math.max(1, math.ceil(config.workerQueueCapacity * saturationThresholdRatio).toInt)

  private def decodeLatency(config: DecoderConfig, job: WorkloadJob): Int = {
    val jitter =
      if (config.latencyJitterMs == 0) 0
      else math.floorMod(MurmurHash3.productHash((job.jobId, job.sequence, job.expectedNumber)), config.latencyJitterMs + 1)
    config.baseDecodeLatencyMs + jitter
  }

  private def roundConfidence(value: Double): Double =
    roundToThreeDecimals(math.max(0.0d, math.min(1.0d, value)))

  private def roundToThreeDecimals(value: Double): Double =
    BigDecimal(value).setScale(3, BigDecimal.RoundingMode.HALF_UP).toDouble

  private def decrementNonNegative(counter: AtomicInteger): Int = {
    var updated = 0
    var done = false
    while (!done) {
      val current = counter.get()
      updated = math.max(0, current - 1)
      done = counter.compareAndSet(current, updated)
    }
    updated
  }

  private def awaitDrain(streamDone: Future[Done]): Unit =
    try {
      Await.ready(streamDone, 5.seconds)
    } catch {
      case _: Throwable => ()
    }

  private def validateConfig(config: DecoderConfig): Either[String, DecoderConfig] = {
    if (config.scenarioId.trim.isEmpty) Left("scenarioId must not be blank")
    else if (config.workerQueueCapacity < 1) Left("workerQueueCapacity must be at least 1")
    else if (config.workerParallelism < 1) Left("workerParallelism must be at least 1")
    else if (config.baseDecodeLatencyMs < 1) Left("baseDecodeLatencyMs must be at least 1")
    else if (config.latencyJitterMs < 0) Left("latencyJitterMs must not be negative")
    else Right(config)
  }

  private def validateJob(job: WorkloadJob): Either[String, WorkloadJob] = {
    if (job.jobId.trim.isEmpty) Left("jobId must not be blank")
    else if (job.scenarioId.trim.isEmpty) Left("scenarioId must not be blank")
    else if (job.sequence < 0) Left("sequence must be zero or positive")
    else if (job.imagePngBase64.trim.isEmpty) Left("imagePngBase64 must not be blank")
    else if (Try(Instant.parse(job.createdAt)).isFailure) Left("createdAt must be an ISO-8601 timestamp")
    else if (Try(Base64.getDecoder.decode(job.imagePngBase64)).isFailure) Left("imagePngBase64 must be valid base64")
    else Right(job)
  }
}
