package com.tokenizedtradingplatform.demo.domain.repository;

import com.tokenizedtradingplatform.demo.domain.model.Trade;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface TradeRepository extends JpaRepository<Trade, UUID> {

    @Query("SELECT t FROM Trade t JOIN FETCH t.asset " +
           "WHERE t.buyer.id = :userId OR t.seller.id = :userId " +
           "ORDER BY t.executedAt DESC")
    Page<Trade> findByUserId(@Param("userId") UUID userId, Pageable pageable);

    @Query("SELECT t FROM Trade t JOIN FETCH t.asset " +
           "WHERE t.asset.id = :assetId " +
           "ORDER BY t.executedAt DESC")
    Page<Trade> findByAssetId(@Param("assetId") Integer assetId, Pageable pageable);

    @Query("SELECT t FROM Trade t JOIN FETCH t.asset " +
           "WHERE t.buyOrder.id = :orderId OR t.sellOrder.id = :orderId " +
           "ORDER BY t.executedAt ASC")
    List<Trade> findByOrderId(@Param("orderId") UUID orderId);
}
