package com.bowt.backend.orderprocessing.infrastructure.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface JpaOrderItemRepository extends JpaRepository<OrderItemEntity, Long> {

    @Modifying
    @Transactional
    @Query("DELETE FROM OrderItemEntity i WHERE i.order.pk = :orderPk")
    void deleteByOrderPk(@Param("orderPk") Long orderPk);
}