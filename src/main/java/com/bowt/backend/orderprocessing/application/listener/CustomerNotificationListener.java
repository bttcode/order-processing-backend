package com.bowt.backend.orderprocessing.application.listener;

import com.bowt.backend.orderprocessing.application.port.out.NotificationService;
import com.bowt.backend.orderprocessing.application.port.out.OrderEventListener;
import com.bowt.backend.orderprocessing.domain.event.OrderEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Sends the customer-facing status update for every transition (not just confirm/cancel)
 * — e.g. "your order is being validated", "payment failed, please retry".
 * <p>
 * This listener must never log customer email/phone at INFO or above.
 * It only logs the orderId and status; the actual recipient contact lookup and message
 * content happen inside the {@link NotificationService} adapter, not here.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CustomerNotificationListener implements OrderEventListener {

    private final NotificationService notificationService;

    @Override
    public void onOrderStatusChanged(OrderEvent event) {
        try {
            notificationService.send(new NotificationService.NotificationRequest(
                    event.orderId().toString(), "customer-email",
                    "Order update", "Your order status changed to " + event.newStatus() + "."));
        } catch (Exception e) {
            log.error("CustomerNotificationListener failed for order {} (event isolated, pipeline unaffected): {}",
                    event.orderId(), e.getMessage(), e);
        }
    }

    @Override
    public String listenerName() {
        return "customer-notification";
    }
}