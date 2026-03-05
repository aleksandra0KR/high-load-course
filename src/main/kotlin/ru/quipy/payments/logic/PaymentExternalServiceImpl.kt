package ru.quipy.payments.logic

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.ktor.client.*
import io.ktor.client.engine.java.Java
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import ru.quipy.common.utils.SlidingWindowRateLimiter
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import ru.quipy.payments.metrics.PaymentMetrics
import java.net.SocketTimeoutException
import java.net.URI
import java.net.http.HttpClient
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
    }

    private val serviceName = properties.serviceName
    private val accountName = properties.accountName

    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(1))
        .version(HttpClient.Version.HTTP_2)
        .build()

    private val rateLimiter = SlidingWindowRateLimiter(
        properties.rateLimitPerSec.toLong(),
        Duration.ofSeconds(1)
    )

    private val semaphore = Semaphore(properties.parallelRequests)

    private val maxRetries = 2
    private val retryDelayMs = 100L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun performPaymentAsync(paymentId: UUID, amount: Int, paymentStartedAt: Long, deadline: Long) {

        val transactionId = UUID.randomUUID()

        val startedAt = now()
        dbScope.launch {
            while (true) {
                try {
                    paymentESService.update(paymentId) {
                        it.logSubmission(
                            success = true,
                            transactionId,
                            startedAt,
                            Duration.ofMillis(startedAt - paymentStartedAt)
                        )
                    }
                    break
                } catch (_: java.lang.IllegalArgumentException) {
                    delay(10)
                }
            }
        }

        logger.info("[$accountName] Submit: $paymentId , txId: $transactionId")

        val result = send(paymentId, amount, transactionId, paymentStartedAt)

        val processedAt = now()
        dbScope.launch {
            while (true) {
                try {
                    paymentESService.update(paymentId) {
                        it.logProcessing(result.status, processedAt, transactionId, reason = result.message)
                    }
                    break
                } catch (_: java.lang.IllegalArgumentException) {
                    delay(10)
                }
            }
        }
    }

    suspend fun send(
        paymentId: UUID,
        amount: Int,
        transactionId: UUID,
        paymentStartedAt: Long
    ): Result = withContext(Dispatchers.IO) {

        repeat(maxRetries) { attempt ->
            try {
                semaphore.withPermit {

                    if (!rateLimiter.tick()) {
                        return@withContext Result(false, "Rate limit exceeded")
                    }

                    val uri = URI.create(
                        "http://$paymentProviderHostPort/external/process" +
                                "?serviceName=$serviceName" +
                                "&token=$token" +
                                "&accountName=$accountName" +
                                "&transactionId=$transactionId" +
                                "&paymentId=$paymentId" +
                                "&amount=$amount"
                    )

                    val request = java.net.http.HttpRequest.newBuilder()
                        .uri(uri)
                        .timeout(Duration.ofSeconds(2))
                        .POST(java.net.http.HttpRequest.BodyPublishers.noBody())
                        .build()

                    val response = client.send(
                        request,
                        java.net.http.HttpResponse.BodyHandlers.ofString()
                    )

                    if (response.statusCode() !in 200..299) {
                        logger.error(
                            "[$accountName] HTTP error ${response.statusCode()} for txId: $transactionId"
                        )
                        return@withContext Result(false, "HTTP ${response.statusCode()}")
                    }

                    val body = try {
                        mapper.readValue(response.body(), ExternalSysResponse::class.java)
                    } catch (e: Exception) {
                        logger.error(
                            "[$accountName] Failed to parse response for txId: $transactionId",
                            e
                        )
                        return@withContext Result(false, "Invalid response")
                    }

                    logger.info(
                        "[$accountName] Payment processed for txId: $transactionId, succeeded: ${body.result}"
                    )

                    return@withContext Result(body.result, body.message)
                }
            } catch (e: Exception) {

                val isLastAttempt = attempt == maxRetries - 1

                when (e) {
                    is SocketTimeoutException -> logger.error(
                        "[$accountName] Timeout for txId: $transactionId (attempt ${attempt + 1})"
                    )
                    else -> logger.error(
                        "[$accountName] Failed for txId: $transactionId (attempt ${attempt + 1})",
                        e
                    )
                }

                if (isLastAttempt) {
                    return@withContext Result(false, e.message)
                }

                delay(retryDelayMs * (attempt + 1))
            }
        }

        Result(false, "Unknown error")
    }

    override fun price() = properties.price
    override fun isEnabled() = properties.enabled
    override fun name() = properties.accountName
    data class Result(val status: Boolean, val message: String?)

}

public fun now() = System.currentTimeMillis()