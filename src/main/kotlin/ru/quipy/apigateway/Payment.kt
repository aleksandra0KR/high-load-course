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

    private val paymentQueue = LinkedBlockingQueue<PaymentTask>()

    private val maxRps = 11
    private val rpsIntervalMs = (1000.0 / maxRps).toLong()

    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    private val workerPool = Executors.newFixedThreadPool(16)

    init {
        logger.info("Starting PaymentQueueProcessor with ~$maxRps RPS")

        scheduler.scheduleAtFixedRate({
            val task = paymentQueue.poll()
            if (task != null) {
                workerPool.submit {
                    try {
                        logger.info("Processing payment task ${task.paymentId} for order ${task.orderId}")
                        orderPayer.processPayment(task.orderId, task.price, task.paymentId, task.deadline)
                        paymentMetrics.markOutgoingResponse()
                    } catch (e: Exception) {
                        logger.error("Failed to process payment ${task.paymentId}: ${e.message}", e)
                    }
                }
            }
        }, 0, rpsIntervalMs, TimeUnit.MILLISECONDS)
    }

    fun submitPaymentTask(orderId: UUID, price: Int, paymentId: UUID, deadline: Long) {
        val task = PaymentTask(orderId, price, paymentId, deadline)
        paymentQueue.put(task)
        logger.debug("Queued payment ${task.paymentId} for order ${task.orderId}. Queue size=${paymentQueue.size}")
    }

    data class PaymentTask(
        val orderId: UUID,
        val price: Int,
        val paymentId: UUID,
        val deadline: Long
    )
}
