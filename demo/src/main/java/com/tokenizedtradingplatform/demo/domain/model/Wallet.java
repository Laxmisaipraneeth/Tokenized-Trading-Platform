package com.tokenizedtradingplatform.demo.domain.model;

import com.tokenizedtradingplatform.demo.exception.InsufficientFundsException;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "wallets")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Wallet {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_id", nullable = false)
    private Asset asset;

    @Column(name = "available_balance", nullable = false, precision = 28, scale = 8)
    @Builder.Default
    private BigDecimal availableBalance = BigDecimal.ZERO;

    @Column(name = "locked_balance", nullable = false, precision = 28, scale = 8)
    @Builder.Default
    private BigDecimal lockedBalance = BigDecimal.ZERO;

    @Version
    @Column(nullable = false)
    private Long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // ── Balance operations ────────────────────────────────────────────────────

    public void credit(BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Credit amount must be positive");
        }
        this.availableBalance = this.availableBalance.add(amount);
    }

    public void debit(BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Debit amount must be positive");
        }
        if (this.availableBalance.compareTo(amount) < 0) {
            throw new InsufficientFundsException(
                    "Insufficient funds in wallet " + id +
                    ": available=" + availableBalance + ", requested=" + amount);
        }
        this.availableBalance = this.availableBalance.subtract(amount);
    }

    // Moves amount from available → locked when an order is placed
    public void reserve(BigDecimal amount) {
        debit(amount);
        this.lockedBalance = this.lockedBalance.add(amount);
    }

    // Moves amount from locked → available when an order is cancelled
    public void releaseReservation(BigDecimal amount) {
        if (this.lockedBalance.compareTo(amount) < 0) {
            throw new IllegalStateException("Cannot release more than locked balance");
        }
        this.lockedBalance = this.lockedBalance.subtract(amount);
        this.availableBalance = this.availableBalance.add(amount);
    }

    // Removes from locked balance during trade settlement (funds move to counterparty)
    public void settleDebit(BigDecimal amount) {
        if (this.lockedBalance.compareTo(amount) < 0) {
            throw new IllegalStateException("Cannot settle more than locked balance");
        }
        this.lockedBalance = this.lockedBalance.subtract(amount);
    }

    public BigDecimal getTotalBalance() {
        return this.availableBalance.add(this.lockedBalance);
    }
}
