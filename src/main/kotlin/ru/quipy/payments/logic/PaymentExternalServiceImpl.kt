package ru.quipy.payments.logic

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.ktor.client.*
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import ru.quipy.common.utils.NonBlockingSlidingWindowRateLimiter
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import ru.quipy.payments.metrics.PaymentMetrics
import java.time.Duration
import java.util.*


class PaymentExternalSystemAdapterImpl(
    private val properties: PaymentAccountProperties,
    private val paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>,
    private val paymentProviderHostPort: String,
    private val token: String,
    private val paymentMetrics: PaymentMetrics,
    private val dbScope: CoroutineScope
) : PaymentExternalSystemAdapter {

    companion object {
        private val logger = LoggerFactory.getLogger(PaymentExternalSystemAdapter::class.java)
        private val mapper = ObjectMapper().registerKotlinModule()
        private const val REQUEST_TIMEOUT_MS = 10_000L
        private const val DEADLINE_BUFFER_MS = 1_000L
        private const val MAX_RETRIES = 3
    }

    private val serviceName = properties.serviceName
    private val accountName = properties.accountName

    private val semaphore = Semaphore(properties.parallelRequests)
    private val rateLimiter = NonBlockingSlidingWindowRateLimiter(
        properties.rateLimitPerSec,
        Duration.ofSeconds(1)
    )
    private val httpClient = HttpClient(Java) {
        install(HttpTimeout) {
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
            connectTimeoutMillis = 2_000L
        }
    }

    override suspend fun performPaymentAsync(paymentId: UUID, amount: Int, paymentStartedAt: Long, deadline: Long) {
        val transactionId = UUID.randomUUID()
        val startedAt = now()

        dbScope.launch {
            while (true) {
                try {
                    paymentESService.update(paymentId) {
                        it.logSubmission(success = true, transactionId, startedAt, Duration.ofMillis(startedAt - paymentStartedAt))
                    }
                    break
                } catch (_: IllegalArgumentException) { delay(10) }
            }
        }

        logger.info("[$accountName] Submit: $paymentId, txId: $transactionId")

        val result = send(paymentId, amount, transactionId, deadline)

        val processedAt = now()
        dbScope.launch {
            while (true) {
                try {
                    paymentESService.update(paymentId) {
                        it.logProcessing(result.status, processedAt, transactionId, reason = result.message)
                    }
                    break
                } catch (_: IllegalArgumentException) { delay(10) }
            }
        }
    }

    private suspend fun send(paymentId: UUID, amount: Int, transactionId: UUID, deadline: Long): Result {
        repeat(MAX_RETRIES) { attempt ->
            if (deadline - now() < REQUEST_TIMEOUT_MS + DEADLINE_BUFFER_MS) {
                return Result(false, "Deadline exceeded")
            }
            if (!rateLimiter.acquireSuspend(50)) {
                return@repeat
            }

            try {
                val response = semaphore.withPermit {
                    httpClient.post(
                        "http://$paymentProviderHostPort/external/process" +
                                "?serviceName=$serviceName&token=$token&accountName=$accountName" +
                                "&transactionId=$transactionId&paymentId=$paymentId&amount=$amount"
                    )
                }

                if (response.status.value !in 200..299) {
                    return Result(false, "HTTP ${response.status.value}")
                }

                val body = mapper.readValue(response.bodyAsText(), ExternalSysResponse::class.java)
                logger.info("[$accountName] Payment processed for txId: $transactionId, succeeded: ${body.result}")
                return Result(body.result, body.message)

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("[$accountName] Attempt ${attempt + 1} failed for txId: $transactionId", e)
                if (attempt < MAX_RETRIES - 1) delay(100L * (attempt + 1))
            }
        }

        return Result(false, "All attempts failed")
    }

    override fun price() = properties.price
    override fun isEnabled() = properties.enabled
    override fun name() = properties.accountName

    data class Result(val status: Boolean, val message: String?)
}

fun now() = System.currentTimeMillis()