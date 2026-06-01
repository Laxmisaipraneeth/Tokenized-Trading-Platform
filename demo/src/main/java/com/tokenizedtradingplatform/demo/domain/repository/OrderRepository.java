package com.tokenizedtradingplatform.demo.domain.repository;

import com.tokenizedtradingplatform.demo.domain.enums.OrderSide;
import com.tokenizedtradingplatform.demo.domain.enums.OrderStatus;
import com.tokenizedtradingplatform.demo.domain.model.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    Page<Order> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    @Query("SELECT o FROM Order o JOIN FETCH o.asset WHERE o.user.id = :userId AND o.status IN :statuses ORDER BY o.createdAt DESC")
    List<Order> findByUserIdAndStatusIn(@Param("userId") UUID userId,
                                        @Param("statuses") List<OrderStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.id = :id")
    Optional<Order> findByIdWithLock(@Param("id") UUID id);

    @Query("SELECT o FROM Order o JOIN FETCH o.user JOIN FETCH o.asset " +
           "WHERE o.asset.id = :assetId AND o.side = :side AND o.status IN :statuses " +
           "ORDER BY o.price ASC, o.createdAt ASC")
    List<Order> findOpenOrdersByAssetAndSide(@Param("assetId") Integer assetId,
                                              @Param("side") OrderSide side,
                                              @Param("statuses") List<OrderStatus> statuses);
}
