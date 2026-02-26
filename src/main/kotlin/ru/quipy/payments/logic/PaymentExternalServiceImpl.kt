package ru.quipy.payments.logic

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.slf4j.LoggerFactory
import ru.quipy.common.utils.SlidingWindowRateLimiter
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import ru.quipy.payments.metrics.PaymentMetrics
import java.net.URI
import java.net.SocketTimeoutException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.*
import java.util.concurrent.*

class PaymentExternalSystemAdapterImpl(
    private val properties: PaymentAccountProperties,
    private val paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>,
    private val paymentProviderHostPort: String,
    private val token: String,
    private val paymentMetrics: PaymentMetrics
) : PaymentExternalSystemAdapter {

    companion object {
        val logger = LoggerFactory.getLogger(PaymentExternalSystemAdapter::class.java)
        val mapper = ObjectMapper().registerKotlinModule()
    }

    private val serviceName = properties.serviceName
    private val accountName = properties.accountName

    private val requestAverageProcessingTime = properties.averageProcessingTime

    private val client = HttpClient.newBuilder()
        .executor(Executors.newFixedThreadPool(60))
        .connectTimeout((Duration.ofSeconds(1)))
        .version(HttpClient.Version.HTTP_2)
        .build()

    private val rateLimiter = SlidingWindowRateLimiter(
        properties.rateLimitPerSec.toLong(),
        Duration.ofSeconds(1)
    )

    private val semaphore = Semaphore(properties.parallelRequests)
    private val maxRetries = 3
    private val retryDelayMs = 100L

    override fun performPaymentAsync(paymentId: UUID, amount: Int, paymentStartedAt: Long, deadline: Long) {
        val transactionId = UUID.randomUUID()

        paymentESService.update(paymentId) {
            it.logSubmission(success = true, transactionId, now(), Duration.ofMillis(now() - paymentStartedAt))
        }
        paymentMetrics.markOutgoingResponse()
        logger.info("[$accountName] Submit: $paymentId, txId: $transactionId")

        processAttempt(paymentId, transactionId, amount, deadline, 1)
    }


    private fun processAttempt(
        paymentId: UUID,
        transactionId: UUID,
        amount: Int,
        deadline: Long,
        attempt: Int
    ) {
        if (now() > deadline) {
            paymentESService.update(paymentId) {
                it.logProcessing(false, now(), transactionId, reason = "Deadline exceeded")
            }
            return
        }

        if (attempt > maxRetries) {
            paymentESService.update(paymentId) {
                it.logProcessing(false, now(), transactionId, reason = "Out of retry time")
            }
            return
        }

        if (attempt > 1) {
            paymentMetrics.retryCounterIncrement()
        }

        rateLimiter.tickBlocking()
        semaphore.acquire()

        val request = HttpRequest.newBuilder()
            .uri(
                URI(
                    "http://$paymentProviderHostPort/external/process" +
                            "?serviceName=$serviceName&token=$token&accountName=$accountName" +
                            "&transactionId=$transactionId&paymentId=$paymentId&amount=$amount"
                )
            )
            .POST(HttpRequest.BodyPublishers.noBody())
            .timeout(Duration.ofSeconds(1))
            .build()

        val start = now()

        client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply { response ->
                semaphore.release()
                val body = try {
                    mapper.readValue(response.body(), ExternalSysResponse::class.java)
                } catch (ex: Exception) {
                    logger.error("[$accountName] JSON parsing fail: ${response.body()}")
                    ExternalSysResponse(
                        transactionId.toString(),
                        paymentId.toString(),
                        false,
                        ex.message
                    )
                }

                logger.warn("[$accountName] Payment result: txId=$transactionId payment=$paymentId ok=${body.result} msg=${body.message}")

                CompletableFuture.runAsync {
                    paymentESService.update(paymentId) {
                        it.logProcessing(body.result, now(), transactionId, reason = body.message)
                    }
                }

                if (!body.result) {
                    CompletableFuture.delayedExecutor(retryDelayMs, TimeUnit.MILLISECONDS)
                        .execute {
                            processAttempt(paymentId, transactionId, amount, deadline, attempt + 1)
                        }
                }
            }
            .exceptionally { e ->
                semaphore.release()
                when (e.cause) {
                    is SocketTimeoutException -> {
                        logger.error(
                            "[$accountName] Payment timeout for txId: $transactionId, payment: $paymentId, retry: ${attempt + 1}/$maxRetries",
                            e
                        )
                        CompletableFuture.runAsync {
                            paymentESService.update(paymentId) {
                                it.logProcessing(false, now(), transactionId, reason = "Request timeout after 10s")
                            }
                        }
                    }
                    else -> {
                        logger.error("[$accountName] Payment failed for txId: $transactionId, payment: $paymentId", e)
                        CompletableFuture.runAsync {
                            paymentESService.update(paymentId) {
                                it.logProcessing(false, now(), transactionId, e.message ?: "Unknown error")
                            }
                        }
                    }
                }
                CompletableFuture.delayedExecutor(retryDelayMs, TimeUnit.MILLISECONDS)
                    .execute {
                        processAttempt(paymentId, transactionId, amount, deadline, attempt + 1)
                    }
            }
            .whenComplete { _, _ ->
                paymentMetrics.addRequestLatency(now(), start)
            }
    }


    override fun price() = properties.price

    override fun isEnabled() = properties.enabled

    override fun name() = properties.accountName

}

public fun now() = System.currentTimeMillis()