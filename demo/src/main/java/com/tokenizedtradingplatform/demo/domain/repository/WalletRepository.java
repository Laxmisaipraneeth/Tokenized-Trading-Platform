package com.tokenizedtradingplatform.demo.domain.repository;

import com.tokenizedtradingplatform.demo.domain.model.Wallet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WalletRepository extends JpaRepository<Wallet, UUID> {

    @Query("SELECT w FROM Wallet w WHERE w.user.id = :userId AND w.asset.id = :assetId")
    Optional<Wallet> findByUserIdAndAssetId(@Param("userId") UUID userId,
                                            @Param("assetId") Integer assetId);

    @Query("SELECT w FROM Wallet w JOIN FETCH w.asset WHERE w.user.id = :userId")
    List<Wallet> findByUserId(@Param("userId") UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM Wallet w WHERE w.id = :id")
    Optional<Wallet> findByIdWithLock(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM Wallet w WHERE w.user.id = :userId AND w.asset.id = :assetId")
    Optional<Wallet> findByUserIdAndAssetIdWithLock(@Param("userId") UUID userId,
                                                    @Param("assetId") Integer assetId);
}
