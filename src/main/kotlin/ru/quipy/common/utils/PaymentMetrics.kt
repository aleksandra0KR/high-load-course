package ru.quipy.payments.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

@Component
class PaymentMetrics(private val registry: MeterRegistry) {
    private val incomingRequestsCounter = registry.counter("payments_incoming_requests_total")

    private val outgoingResponsesCounter = registry.counter("payments_outgoing_responses_total")

    private val successCounter = Counter.builder("payments_completed_successful_requests_total")
        .tag("outcome", "success")
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
}
