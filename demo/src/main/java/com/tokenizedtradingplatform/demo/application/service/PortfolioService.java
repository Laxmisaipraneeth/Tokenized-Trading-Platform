package com.tokenizedtradingplatform.demo.application.service;

import com.tokenizedtradingplatform.demo.api.response.PortfolioResponse;
import com.tokenizedtradingplatform.demo.api.response.WalletResponse;
import com.tokenizedtradingplatform.demo.domain.model.Wallet;
import com.tokenizedtradingplatform.demo.domain.repository.WalletRepository;
import com.tokenizedtradingplatform.demo.infrastructure.cache.PortfolioCacheAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PortfolioService {

    private final WalletRepository walletRepository;
    private final PortfolioCacheAdapter cache;

    @Transactional(readOnly = true)
    public PortfolioResponse getPortfolio(UUID userId) {
        return cache.get(userId).orElseGet(() -> {
            PortfolioResponse fresh = loadFromDb(userId);
            cache.put(userId, fresh);
            return fresh;
        });
    }

    private PortfolioResponse loadFromDb(UUID userId) {
        List<WalletResponse> wallets = walletRepository.findByUserId(userId)
                .stream()
                .map(this::toResponse)
                .toList();
        return new PortfolioResponse(userId, wallets, Instant.now());
    }

    private WalletResponse toResponse(Wallet w) {
        return new WalletResponse(
                w.getId(),
                w.getAsset().getSymbol(),
                w.getAsset().getName(),
                w.getAvailableBalance(),
                w.getLockedBalance(),
                w.getTotalBalance()
        );
    }
}
