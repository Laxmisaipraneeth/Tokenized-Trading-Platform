package com.tokenizedtradingplatform.demo.api.controller;

import com.tokenizedtradingplatform.demo.api.request.PlaceOrderRequest;
import com.tokenizedtradingplatform.demo.api.response.OrderBookResponse;
import com.tokenizedtradingplatform.demo.api.response.OrderResponse;
import com.tokenizedtradingplatform.demo.application.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    // POST /api/v1/orders
    @PostMapping("/orders")
    public ResponseEntity<OrderResponse> placeOrder(
            @AuthenticationPrincipal UUID userId,
            @Valid @RequestBody PlaceOrderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(orderService.placeOrder(userId, request));
    }

    // DELETE /api/v1/orders/{orderId}
    @DeleteMapping("/orders/{orderId}")
    public ResponseEntity<OrderResponse> cancelOrder(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID orderId) {
        return ResponseEntity.ok(orderService.cancelOrder(userId, orderId));
    }

    // GET /api/v1/orders?page=0&size=20
    @GetMapping("/orders")
    public ResponseEntity<Page<OrderResponse>> getUserOrders(
            @AuthenticationPrincipal UUID userId,
            Pageable pageable) {
        return ResponseEntity.ok(orderService.getUserOrders(userId, pageable));
    }

    // GET /api/v1/orders/open
    @GetMapping("/orders/open")
    public ResponseEntity<List<OrderResponse>> getOpenOrders(
            @AuthenticationPrincipal UUID userId) {
        return ResponseEntity.ok(orderService.getOpenOrders(userId));
    }

    // GET /api/v1/orderbook/{symbol}
    @GetMapping("/orderbook/{symbol}")
    public ResponseEntity<OrderBookResponse> getOrderBook(@PathVariable String symbol) {
        return ResponseEntity.ok(orderService.getOrderBook(symbol.toUpperCase()));
    }
}
