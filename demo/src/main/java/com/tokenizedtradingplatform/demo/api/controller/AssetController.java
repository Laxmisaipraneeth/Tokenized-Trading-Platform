package com.tokenizedtradingplatform.demo.api.controller;

import com.tokenizedtradingplatform.demo.api.response.AssetResponse;
import com.tokenizedtradingplatform.demo.domain.repository.AssetRepository;
import com.tokenizedtradingplatform.demo.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/assets")
@RequiredArgsConstructor
public class AssetController {

    private final AssetRepository assetRepository;

    // GET /api/v1/assets — all tradeable assets
    @GetMapping
    public ResponseEntity<List<AssetResponse>> listAssets() {
        List<AssetResponse> assets = assetRepository.findByTradeableTrue()
                .stream()
                .map(a -> new AssetResponse(a.getId(), a.getSymbol(), a.getName(),
                        a.getDecimalPlaces(), a.isTradeable()))
                .toList();
        return ResponseEntity.ok(assets);
    }

    // GET /api/v1/assets/{id}
    @GetMapping("/{id}")
    public ResponseEntity<AssetResponse> getAsset(@PathVariable Integer id) {
        return assetRepository.findById(id)
                .map(a -> ResponseEntity.ok(new AssetResponse(
                        a.getId(), a.getSymbol(), a.getName(), a.getDecimalPlaces(), a.isTradeable())))
                .orElseThrow(() -> new ResourceNotFoundException("Asset not found: " + id));
    }
}
