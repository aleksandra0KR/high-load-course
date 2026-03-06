package ru.quipy.payments.logic

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlinx.coroutines.*
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

        private const val HEDGE_DELAY_MS = 50L
        private const val HTTP_TIMEOUT_MS = 350L
        private const val MAX_RETRIES = 2
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

        val result = processWithHedging(
            paymentId,
            transactionId,
            amount,
            deadline
        )

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

    private suspend fun processWithHedging(
        paymentId: UUID,
        transactionId: UUID,
        amount: Int,
        deadline: Long
    ): Boolean = coroutineScope {

        val first = async {
            processAttempt(paymentId, transactionId, amount, deadline)
        }

        val second = async {
            delay(HEDGE_DELAY_MS)
            processAttempt(paymentId, transactionId, amount, deadline)
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

    private suspend fun processAttempt(
        paymentId: UUID,
        transactionId: UUID,
        amount: Int,
        deadline: Long
    ): Boolean {

        repeat(MAX_RETRIES) { retry ->

            if (now() > deadline) {
                return false
            }

            try {

                semaphore.withPermit {

                    if (!rateLimiter.tick()) {
                        return false
                    }

                    val request = HttpRequest.newBuilder()
                        .uri(
                            URI(
                                "http://$paymentProviderHostPort/external/process" +
                                        "?serviceName=$serviceName&token=$token&accountName=$accountName" +
                                        "&transactionId=$transactionId&paymentId=$paymentId&amount=$amount"
                            )
                        )
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .header("x-idempotency-key",token)
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

                        logger.info(
                            "[$accountName] Payment processed for txId: $transactionId"
                        )

                        return true
                    }
                }

            } catch (e: Exception) {

                logger.warn(
                    "[$accountName] attempt ${retry + 1} failed for txId: $transactionId",
                    e
                )

                if (retry == MAX_RETRIES - 1) {
                    return false
                }

            }
        }

        return false
    }

    override fun price() = properties.price

    override fun isEnabled() = properties.enabled

    override fun name() = properties.accountName

}

public fun now() = System.currentTimeMillis()