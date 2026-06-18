package com.bowt.backend.orderprocessing.application.config;

import com.bowt.backend.orderprocessing.application.port.in.CreateOrderUseCase;
import com.bowt.backend.orderprocessing.application.port.in.QueryOrderUseCase;
import com.bowt.backend.orderprocessing.application.port.out.OrderRepository;
import com.bowt.backend.orderprocessing.domain.service.InventoryService;
import com.bowt.backend.orderprocessing.domain.service.OrderService;
import com.bowt.backend.orderprocessing.domain.service.PaymentService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@Configuration
@EnableTransactionManagement
public class UseCaseConfiguration {

    /**
     * Wire domain service to both use-case ports.
     * Spring sees CreateOrderUseCase and QueryOrderUseCase as the injection targets —
     * OrderService is an implementation detail hidden behind the ports.
     * <p>
     * NOTE: @Transactional on OrderService.createOrder() works here because
     * Spring wraps the bean in a CGLIB proxy — the interface is the contract,
     * CGLIB proxies the concrete class.
     */
    @Bean
    public CreateOrderUseCase createOrderUseCase(
            OrderRepository orderRepository,
            InventoryService inventoryService,
            PaymentService paymentService) {
        return new OrderService(orderRepository, inventoryService, paymentService);
    }

    @Bean
    public QueryOrderUseCase queryOrderUseCase(
            OrderRepository orderRepository,
            InventoryService inventoryService,
            PaymentService paymentService) {
        // Same OrderService instance satisfies both ports.
        // Acceptable in Phase 2; Phase 4 may split them if the class grows.
        return new OrderService(orderRepository, inventoryService, paymentService);
    }
}