package ru.quipy.payments.logic

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.future.await
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import ru.quipy.common.utils.SlidingWindowRateLimiter
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import ru.quipy.payments.metrics.PaymentMetrics
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.*
import java.util.concurrent.TimeUnit

class PaymentExternalSystemAdapterImpl(
    private val properties: PaymentAccountProperties,
    private val paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>,
    private val paymentProviderHostPort: String,
    private val token: String,
    private val paymentMetrics: PaymentMetrics,
    private val dbScope: CoroutineScope
) : PaymentExternalSystemAdapter {

    companion object {
        val logger = LoggerFactory.getLogger(PaymentExternalSystemAdapter::class.java)
        val mapper = ObjectMapper().registerKotlinModule()

        private const val HEDGE_DELAY_MS = 200L
        private const val HTTP_TIMEOUT_MS = 350L
        private const val MAX_RETRIES = 2
        private const val QUEUE_CAPACITY = 1000
        private const val QUEUE_WORKERS = 2
        private const val CB_RETRY_DELAY_MS = 100L
    }

    private val serviceName = properties.serviceName
    private val accountName = properties.accountName

    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .version(HttpClient.Version.HTTP_2)
        .build()

    private val rateLimiter = SlidingWindowRateLimiter(
        properties.rateLimitPerSec.toLong(),
        Duration.ofSeconds(1)
    )

    private val semaphore = Semaphore(properties.parallelRequests)

    private val circuitBreaker: CircuitBreaker = CircuitBreaker.of(
        "payment-$accountName",
        CircuitBreakerConfig.custom()
            .slidingWindowType(SlidingWindowType.TIME_BASED)
            .slidingWindowSize(2)
            .failureRateThreshold(20.0f)
            .minimumNumberOfCalls(10)
            .waitDurationInOpenState(Duration.ofSeconds(1))
            .permittedNumberOfCallsInHalfOpenState(5)
            .build()
    )

    private val queue = Channel<QueuedPayment>(
        capacity = QUEUE_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class QueuedPayment(
        val paymentId: UUID,
        val transactionId: UUID,
        val amount: Int,
        val deadline: Long
    )

    init {
        repeat(QUEUE_WORKERS) {
            workerScope.launch {
                processQueue()
            }
        }
    }

    override suspend fun performPaymentAsync(
        paymentId: UUID,
        amount: Int,
        paymentStartedAt: Long,
        deadline: Long
    ) {

        val transactionId = UUID.randomUUID()

        val startedAt = now()

        dbScope.launch {
            try {
                paymentESService.update(paymentId) {
                    it.logSubmission(
                        success = true,
                        transactionId,
                        startedAt,
                        Duration.ofMillis(startedAt - paymentStartedAt)
                    )
                }
            } catch (e: Exception) {
                logger.error("Submission log failed", e)
            }
        }

        logger.info("[$accountName] Submit: $paymentId , txId: $transactionId")

        if (!circuitBreaker.tryAcquirePermission()) {
            logger.warn("[$accountName] CB OPEN → enqueue $paymentId")
            queue.trySend(QueuedPayment(paymentId, transactionId, amount, deadline))
            return
        }

        val result = executeWithCircuitBreaker { useHedge ->
            if (useHedge) processWithHedging(paymentId, transactionId, amount, deadline)
            else processSingle(paymentId, transactionId, amount, deadline)
        }

        logProcessing(paymentId, transactionId, result)
    }

    private suspend fun processQueue() {
        for (task in queue) {
            if (now() > task.deadline) {
                logger.warn("Expired in queue → ${task.paymentId}")
                continue
            }

            while (!circuitBreaker.tryAcquirePermission()) {
                delay(CB_RETRY_DELAY_MS)
            }

            val result = executeWithCircuitBreaker { useHedge ->
                if (useHedge) processWithHedging(task.paymentId, task.transactionId, task.amount, task.deadline)
                else processSingle(task.paymentId, task.transactionId, task.amount, task.deadline)
            }

            logProcessing(task.paymentId, task.transactionId, result)
        }
    }

    private suspend fun executeWithCircuitBreaker(block: suspend (useHedge: Boolean) -> Boolean): Boolean {
        val start = now()

        val useHedge = circuitBreaker.state != CircuitBreaker.State.HALF_OPEN

        if (!useHedge) {
            logger.info("[$accountName] HALF_OPEN → hedge disabled")
        }

        return try {
            val result = block(useHedge)
            val duration = now() - start

            if (result) {
                circuitBreaker.onSuccess(duration, TimeUnit.MILLISECONDS)
            } else {
                circuitBreaker.onError(duration, TimeUnit.MILLISECONDS, RuntimeException("Failed"))
            }

            result

        } catch (e: Exception) {
            circuitBreaker.onError(0, TimeUnit.MILLISECONDS, e)
            false
        }
    }

    private suspend fun processWithHedging(paymentId: UUID, transactionId: UUID, amount: Int, deadline: Long): Boolean = coroutineScope {

        val first = async {
                processAttempt(paymentId, transactionId, amount, deadline, MAX_RETRIES)
            }
            val second = async {
                delay(HEDGE_DELAY_MS)
                processAttempt(paymentId, transactionId, amount, deadline, MAX_RETRIES)
            }

            select<Boolean> {

                first.onAwait {
                    second.cancel()
                    it
                }

                second.onAwait {
                    first.cancel()
                    it
                }
            }
        }

    private suspend fun processSingle(paymentId: UUID, transactionId: UUID, amount: Int, deadline: Long): Boolean =
        processAttempt(paymentId, transactionId, amount, deadline, 1)

    private suspend fun processAttempt(
        paymentId: UUID,
        transactionId: UUID,
        amount: Int,
        deadline: Long,
        retries: Int
    ): Boolean {
        repeat(retries) { retry ->
            if (now() > deadline) {
                return false
            }

            try {

                semaphore.withPermit {

                    if (!rateLimiter.tick()) {
                        return false
                    }

                    val result = executeExternalCall(
                        paymentId,
                        transactionId,
                        amount
                    )
                    if (result) return true
                }
            } catch (e: Exception) {
                logger.warn("[$accountName] attempt ${retry + 1} failed for txId: $transactionId", e)
                if (retry == retries - 1) return false
            }
        }
        return false
    }

    private suspend fun executeExternalCall(paymentId: UUID, transactionId: UUID, amount: Int): Boolean {
        val request = HttpRequest.newBuilder()
            .uri(
                URI(
                    "http://$paymentProviderHostPort/external/process" +
                            "?serviceName=$serviceName&token=$token&accountName=$accountName" +
                            "&transactionId=$transactionId&paymentId=$paymentId&amount=$amount"
                )
            )
            .POST(HttpRequest.BodyPublishers.noBody())
            .header("x-idempotency-key", token)
            .timeout(Duration.ofMillis(HTTP_TIMEOUT_MS))
            .build()

        val response = client
            .sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .await()

        val body = mapper.readValue(
            response.body(),
            ExternalSysResponse::class.java
        )

        if (body.result) {
            logger.info("[$accountName] Payment processed for txId: $transactionId")
            return true
        }

        return false
    }

    private fun logProcessing(paymentId: UUID, transactionId: UUID, result: Boolean) {
        val processedAt = now()
        dbScope.launch {
            try {
                paymentESService.update(paymentId) {
                    it.logProcessing(result, processedAt, transactionId)
                }

            } catch (e: Exception) {
                logger.error("Processing log failed", e)
            }
        }


    }

    override fun price() = properties.price

    override fun isEnabled() = properties.enabled

    override fun name() = properties.accountName

}

fun now() = System.currentTimeMillis()