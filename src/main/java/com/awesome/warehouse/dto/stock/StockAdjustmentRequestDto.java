package com.awesome.warehouse.dto.stock;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Body of {@code POST /internal/v1/stock/adjustments}. The backend validates the admin input
 * (non-zero delta, requestId present) before calling; these constraints only reject malformed calls.
 */
@Schema(requiredProperties = {"productId", "delta", "reason", "requestId", "actor"})
public record StockAdjustmentRequestDto(
        @NotNull @Positive @Schema(example = "1") Long productId,
        @NotNull @Schema(example = "-3") Integer delta,
        @NotBlank @Size(max = 500) @Schema(example = "Damaged") String reason,
        @NotNull UUID requestId,
        @NotBlank @Size(max = 255) @Schema(example = "admin") String actor) {
}
