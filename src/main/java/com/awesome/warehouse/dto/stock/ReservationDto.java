package com.awesome.warehouse.dto.stock;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/** A reservation's lines in product id order; the body of reserve and confirm. */
@Schema(requiredProperties = {"reservationId", "lines"})
public record ReservationDto(UUID reservationId, List<ReservedLineDto> lines) {

    public ReservationDto {
        lines = List.copyOf(lines);
    }
}
