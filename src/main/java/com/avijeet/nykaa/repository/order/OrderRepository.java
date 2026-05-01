package com.avijeet.nykaa.repository.order;

import com.avijeet.nykaa.entities.order.Order;
import com.avijeet.nykaa.enums.OrderState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {
    Optional<Order> findByUserIdAndStatus(Long userId, OrderState status);
    List<Order> findAllByUserId(Long userId);
}
