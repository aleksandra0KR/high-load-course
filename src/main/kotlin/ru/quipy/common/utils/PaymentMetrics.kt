package ru.quipy.payments.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import io.micrometer.core.instrument.Timer
import java.util.concurrent.TimeUnit

@Component
class PaymentMetrics(private val registry: MeterRegistry) {
    private val incomingRequestsCounter = registry.counter("payments_incoming_requests_total")

    private val outgoingResponsesCounter = registry.counter("payments_outgoing_responses_total")

    private val successCounter = Counter.builder("payments_completed_successful_requests_total")
        .tag("outcome", "success")
        .register(registry)

    private val retryCounter = Counter.builder("payment_retry_count")
        .description("Number of payment retries")
        .register(registry)

    private val requestLatency = Timer.builder("payment_request_latency")
        .description("Payment request latency with quantiles")
        .publishPercentiles(0.5, 0.85, 0.99)
        .register(registry)

    fun markIncomingRequest() {
        incomingRequestsCounter.increment()
    }

    fun markOutgoingResponse() {
        outgoingResponsesCounter.increment()
    }

    fun markSuccessfulRequest() {
        successCounter.increment()
    }

    fun retryCounterIncrement() {
        retryCounter.increment()
    }

    fun addRequestLatency(requestFinishTime: Long, requestStartTime: Long) {
        requestLatency.record(requestFinishTime - requestStartTime, TimeUnit.MILLISECONDS)
    }
}
