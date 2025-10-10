package ru.quipy.payments.metrics

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

@Component
class PaymentMetrics(registry: MeterRegistry) {
    private val incomingRequestsCounter = registry.counter("payments_incoming_requests_total")

    private val outgoingResponsesCounter = registry.counter("payments_outgoing_responses_total")

    private val completedRequestsCounter = registry.counter("payments_completed_requests_total")

    fun markIncomingRequest() {
        incomingRequestsCounter.increment()
    }

    fun markOutgoingResponse() {
        outgoingResponsesCounter.increment()
    }

    fun markCompletedTask() {
        completedRequestsCounter.increment()
    }
}
