package com.tokenizedtradingplatform.demo.api.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PortfolioResponse(
        UUID userId,
        List<WalletResponse> wallets,
        Instant snapshotAt
) {}
