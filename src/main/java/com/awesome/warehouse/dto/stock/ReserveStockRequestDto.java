package com.awesome.warehouse.dto.stock;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** Body of {@code POST /internal/v1/stock/reservations}; lines are unique per product. */
@Schema(requiredProperties = {"reservationId", "username", "lines"})
public record ReserveStockRequestDto(
        @NotNull UUID reservationId,
        @NotBlank @Size(max = 255) @Schema(example = "alice") String username,
        @NotEmpty @Size(max = 100) List<@Valid @NotNull StockLineDto> lines) {
}
