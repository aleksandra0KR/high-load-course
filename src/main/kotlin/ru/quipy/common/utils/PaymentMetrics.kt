package ru.quipy.payments.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

@Component
class PaymentMetrics(private val registry: MeterRegistry) {
    private val incomingRequestsCounter = registry.counter("payments_incoming_requests_total")

    private val outgoingResponsesCounter = registry.counter("payments_outgoing_responses_total")
    
    fun markIncomingRequest() {
        incomingRequestsCounter.increment()
    }

    fun markOutgoingResponse() {
        outgoingResponsesCounter.increment()
    }

    fun markCompletedRequest(success: Boolean) {
        val outcome = if (success) "success" else "fail"
        Counter.builder("payments_completed_requests_total")
            .tag("outcome", outcome)
            .register(registry)
            .increment()
    }
}
