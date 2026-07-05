package com.bowt.backend.orderprocessing.application.listener;

import com.bowt.backend.orderprocessing.application.port.out.NotificationService;
import com.bowt.backend.orderprocessing.application.port.out.OrderEventListener;
import com.bowt.backend.orderprocessing.domain.event.OrderEvent;
import com.bowt.backend.orderprocessing.domain.model.enumeration.OrderStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Notifies the warehouse system when an order is confirmed (pick/pack trigger)
 * or cancelled (stop shipment if already queued).
 * <p>
 * This class is dispatched by {@code EventNotificationAdapter} (infrastructure, not in this delivery)
 * which is expected to wrap each listener call in a try/catch. As defense in depth, this listener also
 * never lets an exception escape {@link #onOrderStatusChanged} — it logs and swallows,
 * since a failed warehouse notification must not roll back or retry the order pipeline itself.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WarehouseListener implements OrderEventListener {

    private final NotificationService notificationService;

    @Override
    public void onOrderStatusChanged(OrderEvent event) {
        try {
            if (event.newStatus() == OrderStatus.CONFIRMED) {
                notificationService.send(new NotificationService.NotificationRequest(
                        event.orderId().toString(), "warehouse-webhook",
                        "Order ready to fulfil", "Order " + event.orderId() + " confirmed — begin pick/pack."));
            } else if (event.newStatus() == OrderStatus.CANCELLED) {
                notificationService.send(new NotificationService.NotificationRequest(
                        event.orderId().toString(), "warehouse-webhook",
                        "Order cancelled", "Order " + event.orderId() + " cancelled — halt shipment if queued."));
            }
        } catch (Exception e) {
            log.error("WarehouseListener failed for order {} (event isolated, pipeline unaffected): {}",
                    event.orderId(), e.getMessage(), e);
        }
    }

    @Override
    public String listenerName() {
        return "warehouse";
    }
}