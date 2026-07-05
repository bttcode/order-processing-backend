package com.bowt.backend.orderprocessing.application.port.out;

import com.bowt.backend.orderprocessing.domain.event.OrderEvent;

/**
 * AR-2 Pattern 3 — Observer. Implemented by {@code WarehouseListener},
 * {@code AccountingListener}, {@code CustomerNotificationListener} in
 * {@code application/listener/}.
 * <p>
 * These listeners perform I/O (notifying external systems), which is why
 * they live in the application layer rather than domain — registered as Spring beans
 */
public interface OrderEventListener {

    void onOrderStatusChanged(OrderEvent event);

    /**
     * Short identifier for logging/metrics — e.g. "warehouse", "accounting".
     */
    String listenerName();
}