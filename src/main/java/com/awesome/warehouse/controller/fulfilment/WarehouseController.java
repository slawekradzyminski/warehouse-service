package com.awesome.warehouse.controller.fulfilment;

import com.awesome.warehouse.dto.ErrorDto;
import com.awesome.warehouse.dto.ValidationErrorsDto;
import com.awesome.warehouse.dto.fulfilment.CreateShipmentDto;
import com.awesome.warehouse.dto.fulfilment.PackShipmentDto;
import com.awesome.warehouse.dto.fulfilment.PickListDto;
import com.awesome.warehouse.dto.fulfilment.ReallocateShipmentDto;
import com.awesome.warehouse.dto.fulfilment.ShortPickDto;
import com.awesome.warehouse.dto.fulfilment.ShipShipmentDto;
import com.awesome.warehouse.dto.fulfilment.ShipmentDto;
import com.awesome.warehouse.dto.tracking.TrackingDto;
import com.awesome.warehouse.dto.order.OrderDto;
import com.awesome.warehouse.dto.order.PageDto;
import com.awesome.warehouse.entity.fulfilment.ShipmentStatus;
import com.awesome.warehouse.security.CustomPrincipal;
import com.awesome.warehouse.service.fulfilment.ParcelAdminService;
import com.awesome.warehouse.service.fulfilment.ShipmentService;
import com.awesome.warehouse.service.tracking.TrackingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ROLE_WAREHOUSE', 'ROLE_ADMIN')")
@RequestMapping("/api/v1/warehouse")
@Tag(name = "Warehouse", description = "Fulfilment queue and shipment state machine for warehouse operators")
@SecurityRequirement(name = "bearerAuth")
@ApiResponse(responseCode = "401", description = "Unauthorized",
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
@ApiResponse(responseCode = "403", description = "Forbidden; requires ROLE_WAREHOUSE or ROLE_ADMIN",
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
@ApiResponse(responseCode = "503", description = "Backend unavailable",
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
public class WarehouseController {

    private static final String TRANSITION_CONFLICT = "Transition not allowed from the current shipment status";

    private final ShipmentService shipmentService;
    private final TrackingService trackingService;
    private final ParcelAdminService parcelAdminService;

    @GetMapping("/queue")
    @Operation(summary = "Get fulfilment queue", description = "Lists PAID orders that have not been released to the warehouse yet, oldest first. Negative page is clamped to zero; size is clamped to 1-100.")
    @ApiResponse(responseCode = "200", description = "Queue returned")
    public PageDto<OrderDto> queue(
            @Parameter(description = "Zero-based page index") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, 1-100") @RequestParam(defaultValue = "20") int size) {
        return shipmentService.queue(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
    }

    @PostMapping("/shipments")
    @Operation(summary = "Create shipment", description = "Releases a PAID order to the warehouse. Each order line is allocated "
            + "to the highest-priority active location that stocks its category (one location for the whole order when one "
            + "location can take every line), and one AWAITING_PICK parcel is created per location. Returns the first parcel; "
            + "parcelCount tells how many were created.")
    @ApiResponse(responseCode = "201", description = "Parcels created; the first one is returned")
    @ApiResponse(responseCode = "400", description = "Invalid request",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ValidationErrorsDto.class)))
    @ApiResponse(responseCode = "404", description = "Order not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    @ApiResponse(responseCode = "409", description = "Order is not PAID, already has a shipment, or no active location stocks one of its categories",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    public ResponseEntity<ShipmentDto> create(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomPrincipal principal,
            @Valid @RequestBody CreateShipmentDto request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(shipmentService.create(request.getOrderId(), principal.username()));
    }

    @GetMapping("/shipments")
    @Operation(summary = "List shipments", description = "Lists shipments oldest first, optionally filtered by status. Negative page is clamped to zero; size is clamped to 1-100.")
    @ApiResponse(responseCode = "200", description = "Shipments returned")
    public PageDto<ShipmentDto> list(
            @RequestParam(required = false) ShipmentStatus status,
            @Parameter(description = "Zero-based page index") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, 1-100") @RequestParam(defaultValue = "20") int size) {
        return PageDto.from(shipmentService.list(status, PageRequest.of(
                Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id")))));
    }

    @GetMapping("/shipments/{id}")
    @Operation(summary = "Get shipment", description = "Returns one parcel with its location, lines and timestamps.")
    @ApiResponse(responseCode = "200", description = "Shipment returned")
    @ApiResponse(responseCode = "404", description = "Shipment not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    public ShipmentDto get(@PathVariable Long id) {
        return shipmentService.get(id);
    }

    @GetMapping("/shipments/{id}/pick-list")
    @Operation(summary = "Get pick list", description = "Returns the parcel's products to pick grouped by product and sorted by category, then name.")
    @ApiResponse(responseCode = "200", description = "Pick list returned")
    @ApiResponse(responseCode = "404", description = "Shipment not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    public PickListDto pickList(@PathVariable Long id) {
        return shipmentService.pickList(id);
    }

    @PostMapping("/shipments/{id}/pick")
    @Operation(summary = "Mark shipment picked", description = "AWAITING_PICK to PICKED.")
    @ApiResponse(responseCode = "200", description = "Shipment picked")
    @ApiResponse(responseCode = "404", description = "Shipment not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    @ApiResponse(responseCode = "409", description = TRANSITION_CONFLICT,
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    public ShipmentDto pick(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomPrincipal principal,
            @PathVariable Long id) {
        return shipmentService.pick(id, principal.username());
    }

    @PostMapping("/shipments/{id}/pack")
    @Operation(summary = "Mark shipment packed", description = "PICKED to PACKED with the package weight (0.01-30.00 kg).")
    @ApiResponse(responseCode = "200", description = "Shipment packed")
    @ApiResponse(responseCode = "400", description = "Invalid weight",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ValidationErrorsDto.class)))
    @ApiResponse(responseCode = "404", description = "Shipment not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    @ApiResponse(responseCode = "409", description = TRANSITION_CONFLICT,
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    public ShipmentDto pack(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomPrincipal principal,
            @PathVariable Long id,
            @Valid @RequestBody PackShipmentDto request) {
        return shipmentService.pack(id, request.getWeightKg(), principal.username());
    }

    @PostMapping("/shipments/{id}/ship")
    @Operation(summary = "Hand shipment to carrier", description = "PACKED to SHIPPED. The tracking number must match the carrier format. "
            + "Without a tracking number the parcel goes to the simulated carrier, which generates a tracking number and moves the parcel "
            + "through IN_TRANSIT and OUT_FOR_DELIVERY to DELIVERED on its own. The order becomes SHIPPED and the customer receives an email.")
    @ApiResponse(responseCode = "200", description = "Shipment shipped")
    @ApiResponse(responseCode = "400", description = "Missing carrier or invalid tracking number",
            content = @Content(mediaType = "application/json", schema = @Schema(anyOf = {ValidationErrorsDto.class, ErrorDto.class})))
    @ApiResponse(responseCode = "404", description = "Shipment not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    @ApiResponse(responseCode = "409", description = TRANSITION_CONFLICT,
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    public ShipmentDto ship(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomPrincipal principal,
            @PathVariable Long id,
            @Valid @RequestBody ShipShipmentDto request) {
        return trackingService.ship(id, request.getCarrier(), request.getTrackingNumber(), principal.username());
    }

    @PostMapping("/shipments/{id}/deliver")
    @Operation(summary = "Confirm delivery", description = "SHIPPED to DELIVERED. The order becomes DELIVERED, which enables reviews and returns, and the customer receives an email.")
    @ApiResponse(responseCode = "200", description = "Shipment delivered")
    @ApiResponse(responseCode = "404", description = "Shipment not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    @ApiResponse(responseCode = "409", description = TRANSITION_CONFLICT,
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    public ShipmentDto deliver(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomPrincipal principal,
            @PathVariable Long id) {
        return trackingService.deliver(id, principal.username());
    }

    @PostMapping("/shipments/{id}/carrier/advance")
    @Operation(summary = "Advance simulated carrier", description = "Fast-forwards a simulated SHIPPED shipment by one carrier scan "
            + "(IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERY_FAILED or DELIVERED) without waiting for the step interval. "
            + "The DELIVERED step delivers the shipment like the deliver endpoint.")
    @ApiResponse(responseCode = "200", description = "Shipment advanced; returns its tracking")
    @ApiResponse(responseCode = "404", description = "Shipment not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    @ApiResponse(responseCode = "409", description = "Not a simulated shipment in status SHIPPED",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    public TrackingDto advanceCarrier(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomPrincipal principal,
            @PathVariable Long id) {
        return trackingService.advance(id, principal.username());
    }

    @PostMapping("/shipments/{id}/cancel")
    @Operation(summary = "Cancel shipment", description = "Cancels an AWAITING_PICK or PICKED parcel together with its order: every "
            + "AWAITING_PICK or PICKED parcel of the order is cancelled, stock is restored and any coupon redemption is released. "
            + "Refused while another parcel of the order is already PACKED or later.")
    @ApiResponse(responseCode = "200", description = "Shipment cancelled")
    @ApiResponse(responseCode = "404", description = "Shipment not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    @ApiResponse(responseCode = "409", description = TRANSITION_CONFLICT,
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    public ShipmentDto cancel(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomPrincipal principal,
            @PathVariable Long id) {
        return shipmentService.cancel(id, principal.username());
    }

    @PostMapping("/shipments/{id}/reallocate")
    @Operation(summary = "Reallocate parcel", description = "Moves an AWAITING_PICK parcel to another active location: the given "
            + "targetLocation, or without it the best other active location that stocks all of the parcel's categories. The lines "
            + "merge into the order's parcel at that location when there is one (the moved parcel is then removed). Returns the "
            + "parcel now holding the lines.")
    @ApiResponse(responseCode = "200", description = "Parcel reallocated")
    @ApiResponse(responseCode = "400", description = "Invalid target location code",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ValidationErrorsDto.class)))
    @ApiResponse(responseCode = "404", description = "Shipment or target location not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    @ApiResponse(responseCode = "409", description = "Parcel is not AWAITING_PICK, or the target is the current, an inactive or "
            + "a non-stocking location, or the order's parcel there is past AWAITING_PICK, or no other location can take it",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    public ShipmentDto reallocate(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomPrincipal principal,
            @PathVariable Long id,
            @Valid @RequestBody(required = false) ReallocateShipmentDto request) {
        return parcelAdminService.reallocate(id, request == null ? null : request.getTargetLocation(), principal.username());
    }

    @PostMapping("/shipments/{id}/short-pick")
    @Operation(summary = "Report short pick", description = "Records units of a product that could not be picked for an "
            + "AWAITING_PICK parcel and moves them to the order's AWAITING_PICK parcel at another active location that stocks the "
            + "category (a new parcel when there is none). A parcel left without lines is removed. Returns the receiving parcel.")
    @ApiResponse(responseCode = "200", description = "Units moved")
    @ApiResponse(responseCode = "400", description = "Missing product or quantity below 1",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ValidationErrorsDto.class)))
    @ApiResponse(responseCode = "404", description = "Shipment not found, or the product is not in it",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    @ApiResponse(responseCode = "409", description = "Parcel is not AWAITING_PICK, the quantity exceeds the line, or no other "
            + "active location stocks the category",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    public ShipmentDto shortPick(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomPrincipal principal,
            @PathVariable Long id,
            @Valid @RequestBody ShortPickDto request) {
        return parcelAdminService.shortPick(id, request.getProductId(), request.getMissingQuantity(), principal.username());
    }
}
