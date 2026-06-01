package com.tokenizedtradingplatform.demo.api.controller;

import com.tokenizedtradingplatform.demo.api.response.TradeResponse;
import com.tokenizedtradingplatform.demo.application.service.TradeExecutionService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/trades")
@RequiredArgsConstructor
public class TradeController {

    private final TradeExecutionService tradeExecutionService;

    // GET /api/v1/trades — current user's trade history (as buyer or seller)
    @GetMapping
    public ResponseEntity<Page<TradeResponse>> getUserTrades(
            @AuthenticationPrincipal UUID userId,
            Pageable pageable) {
        return ResponseEntity.ok(tradeExecutionService.getUserTrades(userId, pageable));
    }

    // GET /api/v1/trades/order/{orderId} — all fills for one of my orders
    @GetMapping("/order/{orderId}")
    public ResponseEntity<List<TradeResponse>> getOrderTrades(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID orderId) {
        return ResponseEntity.ok(tradeExecutionService.getOrderTrades(userId, orderId));
    }

    // GET /api/v1/trades/asset/{assetId} — public asset market history
    @GetMapping("/asset/{assetId}")
    public ResponseEntity<Page<TradeResponse>> getAssetTrades(
            @PathVariable Integer assetId,
            Pageable pageable) {
        return ResponseEntity.ok(tradeExecutionService.getAssetTrades(assetId, pageable));
    }
}
