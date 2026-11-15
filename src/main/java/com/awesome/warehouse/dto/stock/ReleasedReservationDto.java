package com.awesome.warehouse.dto.stock;

import com.awesome.warehouse.entity.stock.ReservationStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/** Body of a release: the reservation and its status ({@code RELEASED}). */
@Schema(requiredProperties = {"reservationId", "status"})
public record ReleasedReservationDto(UUID reservationId, ReservationStatus status) {
}
