package com.bowt.backend.orderprocessing.infrastructure.notification;

import com.bowt.backend.orderprocessing.application.port.out.OrderEventListener;
import com.bowt.backend.orderprocessing.application.port.out.OrderEventPublisher;
import com.bowt.backend.orderprocessing.domain.event.OrderEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * FR-7 / AR-2 Pattern 3 (Observer). Implements the domain-facing OrderEventPublisher
 * port; dispatches to every registered OrderEventListener (WarehouseListener,
 * AccountingListener, CustomerNotificationListener) asynchronously on a dedicated
 * executor — never the calling (order-pipeline) thread.
 * <p>
 * [NFR-3] "If a notification subscriber throws, the main order pipeline must not fail."
 * Each listener invocation is wrapped individually: one listener throwing does not stop
 * the remaining listeners from running, and never propagates back to the order pipeline,
 * since dispatch already happened on notificationExecutor by the time listeners run.
 * <p>
 * [OI-4] Delivery guarantee is "at-least-once within this JVM process" only — there is
 * no durable outbox or broker. If the JVM crashes between publish() and a listener
 * completing, that notification is lost. Acceptable per the documented learning-scope
 * constraint; do not represent this as durable delivery in any documentation.
 */
@Component
@Slf4j
public class EventNotificationAdapter implements OrderEventPublisher {

    private final List<OrderEventListener> listeners;
    private final ThreadPoolExecutor notificationExecutor;

    public EventNotificationAdapter(List<OrderEventListener> listeners,
                                    @Qualifier("notificationExecutor") ThreadPoolExecutor notificationExecutor) {
        this.listeners = listeners;
        this.notificationExecutor = notificationExecutor;
    }

    @Override
    public void publish(OrderEvent event) {
        for (OrderEventListener listener : listeners) {
            notificationExecutor.submit(() -> dispatchSafely(listener, event));
        }
    }

    private void dispatchSafely(OrderEventListener listener, OrderEvent event) {
        try {
            listener.onOrderStatusChanged(event);
        } catch (Exception ex) {
            // Isolation boundary — a broken listener must never affect the order pipeline
            // or other listeners. Log loudly; do not rethrow.
            log.error("OrderEventListener {} threw while handling event {} for order {}: {}",
                    listener.getClass().getSimpleName(), event.newStatus(), event.orderId(), ex.getMessage(), ex);
        }
    }
}