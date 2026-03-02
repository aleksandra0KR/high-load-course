package ru.quipy.payments.logic

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
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

    private val maxRetries = 3
    private val retryDelayMs = 100L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun performPaymentAsync(
        paymentId: UUID,
        amount: Int,
        paymentStartedAt: Long,
        deadline: Long
    ) {
        val transactionId = UUID.randomUUID()

        dbScope.launch {
            while (true) {
                try {
                    paymentESService.update(paymentId) {
                        it.logSubmission(
                            success = true,
                            transactionId,
                            now(),
                            Duration.ofMillis(now() - paymentStartedAt)
                        )
                    }
                    break
                } catch (_: java.lang.IllegalArgumentException) {
                    delay(10)
                }
            }
        }

        paymentMetrics.markOutgoingResponse()
        logger.info("[$accountName] Submit: $paymentId, txId: $transactionId")

        processAttempt(paymentId, transactionId, amount, deadline, 1)
    }

    private suspend fun processAttempt(
        paymentId: UUID,
        transactionId: UUID,
        amount: Int,
        deadline: Long,
        attempt: Int
    ) {
        if (now() > deadline) {


            dbScope.launch {
                while (true) {
                    try {
                        paymentESService.update(paymentId) {
                            it.logProcessing(false, now(), transactionId, "Deadline exceeded")
                        }
                        break
                    } catch (_: java.lang.IllegalArgumentException) {
                        delay(10)
                    }
                }
            }




            return
        }

        if (attempt > maxRetries) {


            dbScope.launch {
                while (true) {
                    try {
                        paymentESService.update(paymentId) {
                            it.logProcessing(false, now(), transactionId, "Out of retry time")
                        }
                        break
                    } catch (_: java.lang.IllegalArgumentException) {
                        delay(10)
                    }
                }
            }




            return
        }

        if (attempt > 1) {
            paymentMetrics.retryCounterIncrement()
        }

        rateLimiter.tickBlocking()

        semaphore.withPermit {
            val request = HttpRequest.newBuilder()
                .uri(
                    URI(
                        "http://$paymentProviderHostPort/external/process" +
                                "?serviceName=$serviceName&token=$token&accountName=$accountName" +
                                "&transactionId=$transactionId&paymentId=$paymentId&amount=$amount"
                    )
                )
                .POST(HttpRequest.BodyPublishers.noBody())
                .build()

            val start = now()

            try {
                val response = client
                    .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .await()

                val body = try {
                    mapper.readValue(response.body(), ExternalSysResponse::class.java)
                } catch (ex: Exception) {
                    logger.error("[$accountName] JSON parsing fail: ${response.body()}")
                    ExternalSysResponse(transactionId.toString(), paymentId.toString(), false, ex.message)
                }

                logger.warn("[$accountName] result tx=$transactionId ok=${body.result}")




                dbScope.launch {
                    while (true) {
                        try {
                            paymentESService.update(paymentId) {
                                it.logProcessing(body.result, now(), transactionId, body.message)
                            }
                            break
                        } catch (_: java.lang.IllegalArgumentException) {
                            delay(10)
                        }
                    }
                }

                if (!body.result) {
                    delay(retryDelayMs)
                    processAttempt(paymentId, transactionId, amount, deadline, attempt + 1)
                }

            } catch (e: Exception) {
                logger.error("[$accountName] error tx=$transactionId", e)



                dbScope.launch {
                    while (true) {
                        try {
                            paymentESService.update(paymentId) {
                                it.logProcessing(false, now(), transactionId, e.message ?: "Unknown error")
                            }
                            break
                        } catch (_: java.lang.IllegalArgumentException) {
                            delay(10)
                        }
                    }
                }





                delay(retryDelayMs)
                processAttempt(paymentId, transactionId, amount, deadline, attempt + 1)
            } finally {
                paymentMetrics.addRequestLatency(now(), start)
            }
        }
    }

    override fun price() = properties.price
    override fun isEnabled() = properties.enabled
    override fun name() = properties.accountName
}

fun now() = System.currentTimeMillis()