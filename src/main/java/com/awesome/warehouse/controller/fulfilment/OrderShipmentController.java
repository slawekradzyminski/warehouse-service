package com.awesome.warehouse.controller.fulfilment;

import com.awesome.warehouse.dto.ErrorDto;
import com.awesome.warehouse.dto.fulfilment.ShipmentDto;
import com.awesome.warehouse.dto.tracking.TrackingDto;
import com.awesome.warehouse.security.CustomPrincipal;
import com.awesome.warehouse.service.fulfilment.ShipmentService;
import com.awesome.warehouse.service.tracking.TrackingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Tag(name = "Orders", description = "Order management endpoints")
@SecurityRequirement(name = "bearerAuth")
@ApiResponse(responseCode = "503", description = "Backend unavailable",
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
public class OrderShipmentController {

    private final ShipmentService shipmentService;
    private final TrackingService trackingService;

    @GetMapping("/api/v1/orders/{id}/shipment")
    @Operation(summary = "Track order shipment", description = "Returns the first parcel of one of the caller's orders, with "
            + "locationCode and parcelCount; GET /api/v1/orders/{id}/shipments lists every parcel. Administrators may track any order.")
    @ApiResponse(responseCode = "200", description = "Shipment returned")
    @ApiResponse(responseCode = "401", description = "Unauthorized",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    @ApiResponse(responseCode = "404", description = "Order not found, or not released to the warehouse yet",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    public ShipmentDto track(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomPrincipal principal,
            @PathVariable Long id) {
        return shipmentService.trackOrder(id, principal.username(), principal.isAdmin());
    }

    @GetMapping("/api/v1/orders/{id}/shipments")
    @Operation(summary = "List order parcels", description = "Returns every parcel of one of the caller's orders, oldest first, "
            + "each with its location, lines and tracking number. Empty while the order is not released to the warehouse. "
            + "Administrators may list any order.")
    @ApiResponse(responseCode = "200", description = "Parcels returned")
    @ApiResponse(responseCode = "401", description = "Unauthorized",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    @ApiResponse(responseCode = "404", description = "Order not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    public List<ShipmentDto> parcels(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomPrincipal principal,
            @PathVariable Long id) {
        return shipmentService.orderShipments(id, principal.username(), principal.isAdmin());
    }

    @GetMapping("/api/v1/orders/{id}/shipment/tracking")
    @Operation(summary = "Track order parcel", description = "Returns the carrier tracking of the order's first parcel (scans, current status, estimated delivery) "
            + "for one of the caller's orders. Administrators may track any order. Before the hand-over to the carrier the events are empty.")
    @ApiResponse(responseCode = "200", description = "Tracking returned")
    @ApiResponse(responseCode = "401", description = "Unauthorized",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    @ApiResponse(responseCode = "404", description = "Order not found, or not released to the warehouse yet",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    public TrackingDto tracking(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomPrincipal principal,
            @PathVariable Long id) {
        return trackingService.trackOrder(id, principal.username(), principal.isAdmin());
    }
}
