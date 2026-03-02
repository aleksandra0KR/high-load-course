package ru.quipy.apigateway

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import ru.quipy.payments.logic.OrderPayer
import ru.quipy.payments.metrics.PaymentMetrics
import java.util.*
import java.util.concurrent.*

@Service
class PaymentQueueProcessor(
    @Autowired private val orderPayer: OrderPayer,
    @Autowired private val paymentMetrics: PaymentMetrics
) {
    private val logger = LoggerFactory.getLogger(PaymentQueueProcessor::class.java)
    private val paymentQueue = LinkedBlockingQueue<PaymentTask>(1_000_000) // Увеличьте размер очереди
    private val workerPool = Executors.newFixedThreadPool(1000) // Увеличьте пул потоков


    fun submitPaymentTask(orderId: UUID, price: Int, paymentId: UUID, deadline: Long) {
        orderPayer.processPayment(orderId, price, paymentId, deadline)
        paymentMetrics.markOutgoingResponse()
    }

    data class PaymentTask(
        val orderId: UUID,
        val price: Int,
        val paymentId: UUID,
        val deadline: Long
    )
}
