package com.tokenizedtradingplatform.demo.domain.model;

import com.tokenizedtradingplatform.demo.domain.enums.OrderSide;
import com.tokenizedtradingplatform.demo.domain.enums.OrderStatus;
import com.tokenizedtradingplatform.demo.domain.enums.OrderType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "orders")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asset_id", nullable = false)
    private Asset asset;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 4)
    private OrderSide side;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 6)
    @Builder.Default
    private OrderType type = OrderType.LIMIT;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private OrderStatus status = OrderStatus.OPEN;

    @Column(nullable = false, precision = 28, scale = 8)
    private BigDecimal price;

    @Column(nullable = false, precision = 28, scale = 8)
    private BigDecimal quantity;

    @Column(nullable = false, precision = 28, scale = 8)
    @Builder.Default
    private BigDecimal filledQuantity = BigDecimal.ZERO;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    public BigDecimal getRemainingQuantity() {
        return quantity.subtract(filledQuantity);
    }

    public void fill(BigDecimal qty) {
        this.filledQuantity = this.filledQuantity.add(qty);
        int cmp = this.filledQuantity.compareTo(this.quantity);
        this.status = (cmp >= 0) ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
    }

    public void cancel() {
        this.status = OrderStatus.CANCELLED;
    }
}
