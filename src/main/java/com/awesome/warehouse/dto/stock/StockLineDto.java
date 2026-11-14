package com.awesome.warehouse.dto.stock;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** One product of a reservation or a stock restore. */
@Schema(requiredProperties = {"productId", "quantity"})
public record StockLineDto(
        @NotNull @Positive @Schema(example = "1") Long productId,
        @NotNull @Positive @Schema(example = "2") Integer quantity) {
}
