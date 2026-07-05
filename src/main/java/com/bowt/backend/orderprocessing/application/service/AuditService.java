package com.bowt.backend.orderprocessing.application.service;

import com.bowt.backend.orderprocessing.domain.model.Order;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class AuditService {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logOrderCreation(Order order) {
        log.info("AUDIT order_created orderId={} customerId={} status={} totalAmount={}",
                order.getId(), order.getCustomerId(), order.getStatus(), order.getTotalAmount());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logOrderCancellation(Order order, String reason) {
        log.info("AUDIT order_cancelled orderId={} customerId={} reason={}",
                order.getId(), order.getCustomerId(), reason);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logPaymentFailure(Order order, String failureReason) {
        log.info("AUDIT payment_failed orderId={} customerId={} reason={}",
                order.getId(), order.getCustomerId(), failureReason);
    }
}