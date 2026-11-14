package com.awesome.warehouse.dto.stock;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** Body of {@code POST /internal/v1/stock/reservations/{id}/confirm}. */
@Schema(requiredProperties = {"orderId"})
public record ConfirmReservationRequestDto(@NotNull @Positive @Schema(example = "123") Long orderId) {
}
