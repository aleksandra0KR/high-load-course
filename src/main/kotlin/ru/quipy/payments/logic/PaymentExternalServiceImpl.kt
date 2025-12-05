package ru.quipy.payments.logic

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.slf4j.LoggerFactory
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import ru.quipy.payments.metrics.PaymentMetrics
import ru.quipy.common.utils.SlidingWindowRateLimiter
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
        .executor(Executors.newFixedThreadPool(110))
        .connectTimeout((Duration.ofMillis(requestAverageProcessingTime.toMillis() * 2)))
        .version(HttpClient.Version.HTTP_2)
        .build()

    private val rateLimiter = SlidingWindowRateLimiter(
        properties.rateLimitPerSec.toLong(),
        Duration.ofSeconds(1)
    )

    private val semaphore = Semaphore(properties.parallelRequests)
    private val maxRetries = 3
    private val retryDelayMs = 150L

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
                URI("http://$paymentProviderHostPort/external/process" +
                        "?serviceName=$serviceName&token=$token&accountName=$accountName" +
                        "&transactionId=$transactionId&paymentId=$paymentId&amount=$amount")
            )
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()

        val start = now()

        client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply { response ->
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

                paymentESService.update(paymentId) {
                    it.logProcessing(body.result, now(), transactionId, reason = body.message)
                }

                if (!body.result) {
                    scheduleRetry(paymentId, transactionId, amount, deadline, attempt)
                }
            }
            .exceptionally { e ->
                handleException(e, paymentId, transactionId)
                scheduleRetry(paymentId, transactionId, amount, deadline, attempt)
            }
            .whenComplete { _, _ ->
                semaphore.release()
                paymentMetrics.addRequestLatency(now(), start)
            }
    }


    private fun scheduleRetry(
        paymentId: UUID,
        transactionId: UUID,
        amount: Int,
        deadline: Long,
        attempt: Int
    ) {
        CompletableFuture.delayedExecutor(retryDelayMs, TimeUnit.MILLISECONDS)
            .execute {
                processAttempt(paymentId, transactionId, amount, deadline, attempt + 1)
            }
    }


    private fun handleException(e: Throwable, paymentId: UUID, transactionId: UUID) {
        when (e.cause) {
            is SocketTimeoutException -> {
                logger.error("[$accountName] Timeout txId=$transactionId", e)
                paymentESService.update(paymentId) {
                    it.logProcessing(false, now(), transactionId, "Timeout")
                }
            }
            else -> {
                logger.error("[$accountName] Error txId=$transactionId", e)
                paymentESService.update(paymentId) {
                    it.logProcessing(false, now(), transactionId, e.message ?: "Unknown error")
                }
            }
        }
    }


    override fun price() = properties.price

    override fun isEnabled() = properties.enabled

    override fun name() = properties.accountName

}

public fun now() = System.currentTimeMillis()