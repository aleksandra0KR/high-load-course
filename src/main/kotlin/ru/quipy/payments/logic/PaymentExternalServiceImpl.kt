package ru.quipy.payments.logic

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.github.resilience4j.ratelimiter.RateLimiter
import io.github.resilience4j.ratelimiter.RateLimiterConfig
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
        val mapper = ObjectMapper().registerKotlinModule()
    }

    private val serviceName = properties.serviceName
    private val accountName = properties.accountName

    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .version(HttpClient.Version.HTTP_1_1)
        .build()

    // 4000 RPS
    private val rateLimiter = RateLimiter.of("rate-limiter", RateLimiterConfig.custom()
        .limitForPeriod(1100)
        .limitRefreshPeriod(Duration.ofMillis(1000))
        .build()
    )

    // 4000 активных запросов (1 сек processing time)
    private val semaphore = Semaphore(2000)

    private val maxRetries = 3
    private val retryDelayMs = 100L

    override fun performPaymentAsync(
        paymentId: UUID,
        amount: Int,
        paymentStartedAt: Long,
        deadline: Long
    ) {
        val transactionId = UUID.randomUUID()

        processAttempt(paymentId, transactionId, amount, deadline, 1)
    }

    private fun processAttempt(
        paymentId: UUID,
        transactionId: UUID,
        amount: Int,
        deadline: Long,
        attempt: Int
    ) {
        if (System.currentTimeMillis() > deadline || attempt > maxRetries) {
            return
        }

        if (!rateLimiter.acquirePermission()) {
            retry(paymentId, transactionId, amount, deadline, attempt)
        }
        if (!semaphore.tryAcquire()) {
            retry(paymentId, transactionId, amount, deadline, attempt)
            return
        }
        val request = HttpRequest.newBuilder()
            .uri(
                URI(
                    "http://$paymentProviderHostPort/external/process" +
                            "?serviceName=$serviceName&token=$token" +
                            "&accountName=$accountName" +
                            "&transactionId=$transactionId" +
                            "&paymentId=$paymentId" +
                            "&amount=$amount"
                )
            )
            .POST(HttpRequest.BodyPublishers.noBody())
            .timeout(Duration.ofSeconds(1))
            .build()

        val start = System.currentTimeMillis()

        client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .whenComplete { response, error ->

                semaphore.release()
                paymentMetrics.addRequestLatency(System.currentTimeMillis(), start)

                if (error != null) {
                    retry(paymentId, transactionId, amount, deadline, attempt)
                    return@whenComplete
                }

                val body = try {
                    mapper.readValue(response.body(), ExternalSysResponse::class.java)
                } catch (ex: Exception) {
                    retry(paymentId, transactionId, amount, deadline, attempt)
                    return@whenComplete
                }

                if (!body.result) {
                    retry(paymentId, transactionId, amount, deadline, attempt)
                }
            }
    }

    private fun retry(
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

    override fun price() = properties.price
    override fun isEnabled() = properties.enabled
    override fun name() = properties.accountName
}

public fun now() = System.currentTimeMillis()