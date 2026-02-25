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
    private val orderPayer: OrderPayer
) {

    fun submitPaymentTask(
        orderId: UUID,
        price: Int,
        paymentId: UUID,
        deadline: Long
    ) {
        orderPayer.processPayment(orderId, price, paymentId, deadline)
    }
}