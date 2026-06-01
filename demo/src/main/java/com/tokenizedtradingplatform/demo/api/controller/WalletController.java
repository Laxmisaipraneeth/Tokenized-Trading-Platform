package com.tokenizedtradingplatform.demo.api.controller;

import com.tokenizedtradingplatform.demo.api.request.DepositRequest;
import com.tokenizedtradingplatform.demo.api.request.WithdrawRequest;
import com.tokenizedtradingplatform.demo.api.response.PortfolioResponse;
import com.tokenizedtradingplatform.demo.api.response.TransactionResponse;
import com.tokenizedtradingplatform.demo.api.response.WalletResponse;
import com.tokenizedtradingplatform.demo.application.service.PortfolioService;
import com.tokenizedtradingplatform.demo.application.service.WalletService;
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
@RequestMapping("/api/v1/wallets")
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;
    private final PortfolioService portfolioService;

    // GET /api/v1/wallets — all wallets for the logged-in user (DB-direct)
    @GetMapping
    public ResponseEntity<List<WalletResponse>> getAllWallets(@AuthenticationPrincipal UUID userId) {
        return ResponseEntity.ok(walletService.getAllWallets(userId));
    }

    // GET /api/v1/wallets/portfolio — cached portfolio snapshot (served from Redis when warm)
    @GetMapping("/portfolio")
    public ResponseEntity<PortfolioResponse> getPortfolio(@AuthenticationPrincipal UUID userId) {
        return ResponseEntity.ok(portfolioService.getPortfolio(userId));
    }

    // GET /api/v1/wallets/{assetId} — single wallet balance
    @GetMapping("/{assetId}")
    public ResponseEntity<WalletResponse> getWallet(@AuthenticationPrincipal UUID userId,
                                                     @PathVariable Integer assetId) {
        return ResponseEntity.ok(walletService.getWallet(userId, assetId));
    }

    // POST /api/v1/wallets/{assetId} — create wallet for an asset
    @PostMapping("/{assetId}")
    public ResponseEntity<WalletResponse> createWallet(@AuthenticationPrincipal UUID userId,
                                                        @PathVariable Integer assetId) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(walletService.getOrCreateWallet(userId, assetId));
    }

    // POST /api/v1/wallets/{assetId}/deposit
    @PostMapping("/{assetId}/deposit")
    public ResponseEntity<WalletResponse> deposit(@AuthenticationPrincipal UUID userId,
                                                   @PathVariable Integer assetId,
                                                   @Valid @RequestBody DepositRequest request) {
        return ResponseEntity.ok(walletService.deposit(userId, assetId, request));
    }

    // POST /api/v1/wallets/{assetId}/withdraw
    @PostMapping("/{assetId}/withdraw")
    public ResponseEntity<WalletResponse> withdraw(@AuthenticationPrincipal UUID userId,
                                                    @PathVariable Integer assetId,
                                                    @Valid @RequestBody WithdrawRequest request) {
        return ResponseEntity.ok(walletService.withdraw(userId, assetId, request));
    }

    // GET /api/v1/wallets/{assetId}/transactions?page=0&size=20
    @GetMapping("/{assetId}/transactions")
    public ResponseEntity<Page<TransactionResponse>> getTransactions(
            @AuthenticationPrincipal UUID userId,
            @PathVariable Integer assetId,
            Pageable pageable) {
        return ResponseEntity.ok(walletService.getTransactionHistory(userId, assetId, pageable));
    }
}
