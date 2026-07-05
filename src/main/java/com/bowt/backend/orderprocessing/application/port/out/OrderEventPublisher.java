package com.bowt.backend.orderprocessing.application.port.out;

import com.bowt.backend.orderprocessing.domain.event.OrderEvent;

public interface OrderEventPublisher {

    void publish(OrderEvent event);
}