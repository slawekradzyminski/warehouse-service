package com.awesome.warehouse.dto.stock;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** Body of {@code PUT /internal/v1/stock/items/{productId}}; {@code requestId} only correlates the call. */
@Schema(requiredProperties = {"initialQuantity", "actor"})
public record InitialStockRequestDto(
        @NotNull @PositiveOrZero @Schema(example = "10") Integer initialQuantity,
        @NotBlank @Size(max = 255) @Schema(example = "admin") String actor,
        @Schema(types = {"string", "null"}) UUID requestId) {
}
