package com.tokenizedtradingplatform.demo.domain.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "assets")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Asset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false, unique = true, length = 10)
    private String symbol;

    @Column(nullable = false)
    private String name;

    @Column(name = "decimal_places", nullable = false)
    @Builder.Default
    private int decimalPlaces = 8;

    @Column(name = "is_tradeable", nullable = false)
    @Builder.Default
    private boolean tradeable = true;

    @Column(name = "is_cash", nullable = false)
    @Builder.Default
    private boolean cash = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
