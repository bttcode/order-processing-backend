package com.bowt.backend.orderprocessing.application.config;

import com.bowt.backend.orderprocessing.application.port.in.CancelOrderUseCase;
import com.bowt.backend.orderprocessing.application.port.in.CreateOrderUseCase;
import com.bowt.backend.orderprocessing.application.port.in.QueryOrderUseCase;
import com.bowt.backend.orderprocessing.application.port.out.OrderEventPublisher;
import com.bowt.backend.orderprocessing.application.port.out.OrderRepository;
import com.bowt.backend.orderprocessing.application.port.out.ProductRepository;
import com.bowt.backend.orderprocessing.application.service.AuditService;
import com.bowt.backend.orderprocessing.application.service.InventoryService;
import com.bowt.backend.orderprocessing.application.service.OrderService;
import com.bowt.backend.orderprocessing.application.service.PaymentService;
import com.bowt.backend.orderprocessing.domain.factory.OrderFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class UseCaseConfiguration {

    @Bean
    public OrderService orderService(OrderRepository orderRepository,
                                     ProductRepository productRepository,
                                     OrderFactory orderFactory,
                                     InventoryService inventoryService,
                                     PaymentService paymentService,
                                     AuditService auditService,
                                     OrderEventPublisher eventPublisher) {
        return new OrderService(orderRepository, productRepository, orderFactory,
                inventoryService, paymentService, auditService, eventPublisher);
    }

    @Bean
    public CreateOrderUseCase createOrderUseCase(OrderService orderService) {
        return orderService;
    }

    @Bean
    public QueryOrderUseCase queryOrderUseCase(OrderService orderService) {
        return orderService;
    }

    @Bean
    public CancelOrderUseCase cancelOrderUseCase(OrderService orderService) {
        return orderService;
    }
}