package com.bowt.backend.orderprocessing.application.listener;

import com.bowt.backend.orderprocessing.application.port.out.NotificationService;
import com.bowt.backend.orderprocessing.application.port.out.OrderEventListener;
import com.bowt.backend.orderprocessing.domain.event.OrderEvent;
import com.bowt.backend.orderprocessing.domain.model.enumeration.OrderStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Records confirmed/cancelled orders for downstream revenue recognition and refund bookkeeping.
 * A failure here is logged and swallowed, never propagated back into the order pipeline.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AccountingListener implements OrderEventListener {

    private final NotificationService notificationService;

    @Override
    public void onOrderStatusChanged(OrderEvent event) {
        try {
            if (event.newStatus() == OrderStatus.CONFIRMED) {
                notificationService.send(new NotificationService.NotificationRequest(
                        event.orderId().toString(), "accounting-export",
                        "Revenue event", "Order " + event.orderId() + " confirmed — record revenue."));
            } else if (event.newStatus() == OrderStatus.CANCELLED) {
                notificationService.send(new NotificationService.NotificationRequest(
                        event.orderId().toString(), "accounting-export",
                        "Refund event", "Order " + event.orderId() + " cancelled — record refund."));
            }
        } catch (Exception e) {
            log.error("AccountingListener failed for order {} (event isolated, pipeline unaffected): {}",
                    event.orderId(), e.getMessage(), e);
        }
    }

    @Override
    public String listenerName() {
        return "accounting";
    }
}