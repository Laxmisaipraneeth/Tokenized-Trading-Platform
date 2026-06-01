package com.tokenizedtradingplatform.demo.domain.repository;

import com.tokenizedtradingplatform.demo.domain.model.Asset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AssetRepository extends JpaRepository<Asset, Integer> {
    Optional<Asset> findBySymbol(String symbol);
    Optional<Asset> findByCashTrue();
    List<Asset> findByTradeableTrue();
}
