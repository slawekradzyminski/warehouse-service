package com.awesome.warehouse.controller.stock;

import com.awesome.warehouse.config.OpenApiConfig;
import com.awesome.warehouse.dto.ErrorDto;
import com.awesome.warehouse.dto.ValidationErrorsDto;
import com.awesome.warehouse.dto.order.PageDto;
import com.awesome.warehouse.dto.stock.ConfirmReservationRequestDto;
import com.awesome.warehouse.dto.stock.InitialStockRequestDto;
import com.awesome.warehouse.dto.stock.InventoryMovementDto;
import com.awesome.warehouse.dto.stock.ReleasedReservationDto;
import com.awesome.warehouse.dto.stock.ReservationDto;
import com.awesome.warehouse.dto.stock.ReserveStockRequestDto;
import com.awesome.warehouse.dto.stock.StockAdjustmentDto;
import com.awesome.warehouse.dto.stock.StockAdjustmentRequestDto;
import com.awesome.warehouse.dto.stock.StockErrorDto;
import com.awesome.warehouse.dto.stock.StockItemDto;
import com.awesome.warehouse.service.stock.StockService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The stock ledger API the backend calls (contract section 2), authenticated with the backend's
 * service token as caller {@code BACKEND} (see {@code BackendServiceTokenFilter}).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/v1/stock")
@Tag(name = "Stock ledger (internal)", description = "Reservations, adjustments, stock items and movements; "
        + "called by the backend with its service token")
@SecurityRequirement(name = OpenApiConfig.BACKEND_SERVICE_TOKEN)
@ApiResponse(responseCode = "401", description = "Missing or invalid backend service token",
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
@ApiResponse(responseCode = "503", description = "Stock ledger not initialised (the startup import is running)",
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
public class InternalStockController {

    private final StockService stockService;

    @PostMapping("/reservations")
    @Operation(summary = "Reserve stock for a checkout",
            description = "Locks the items in product id order, checks every line, then deducts every line with an "
                    + "ORDER_DEDUCTED movement. The reservation stays PENDING_CONFIRMATION until confirmed. A repeated "
                    + "call with the same reservationId and lines returns the stored reservation.")
    @ApiResponse(responseCode = "201", description = "Stock reserved (or the stored reservation replayed)")
    @ApiResponse(responseCode = "400", description = "Invalid request or a product listed twice",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ValidationErrorsDto.class)))
    @ApiResponse(responseCode = "404", description = "A product has no stock item; nothing was deducted",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = StockErrorDto.class)))
    @ApiResponse(responseCode = "409", description = "Insufficient stock (the first short product in id order), "
            + "or the reservationId was used with different lines; nothing was deducted",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = StockErrorDto.class)))
    public ResponseEntity<ReservationDto> reserve(@Valid @RequestBody ReserveStockRequestDto request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(stockService.reserve(request));
    }

    @PostMapping("/reservations/{reservationId}/confirm")
    @Operation(summary = "Confirm a reservation",
            description = "Marks the reservation CONFIRMED for the order and sets the order id on its movements. "
                    + "Idempotent for the same order.")
    @ApiResponse(responseCode = "200", description = "Reservation confirmed")
    @ApiResponse(responseCode = "400", description = "Invalid request",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ValidationErrorsDto.class)))
    @ApiResponse(responseCode = "404", description = "Reservation not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    @ApiResponse(responseCode = "409", description = "Reservation released, or confirmed for another order",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    public ReservationDto confirm(@PathVariable UUID reservationId,
                                  @Valid @RequestBody ConfirmReservationRequestDto request) {
        return stockService.confirm(reservationId, request.orderId());
    }

    @PostMapping("/reservations/{reservationId}/release")
    @Operation(summary = "Release a reservation",
            description = "Puts back the stock of a PENDING_CONFIRMATION reservation (ORDER_RESTORED movements by "
                    + "\"system\", reason \"Reservation released\") and marks it RELEASED. Repeated calls return the "
                    + "same body. Confirmed reservations are restored only through order.cancelled.")
    @ApiResponse(responseCode = "200", description = "Reservation released")
    @ApiResponse(responseCode = "404", description = "Reservation not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    @ApiResponse(responseCode = "409", description = "Reservation already confirmed",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    public ReleasedReservationDto release(@PathVariable UUID reservationId) {
        return stockService.release(reservationId);
    }

    @PostMapping("/adjustments")
    @Operation(summary = "Adjust stock",
            description = "Admin adjustment with the backend's former rules: a replay of (productId, requestId) "
                    + "with the same delta and reason returns the stored movement; the result must stay within "
                    + "0..2147483647. Returns the movement plus the ledger version it left.")
    @ApiResponse(responseCode = "201", description = "Adjustment recorded (or the stored one replayed)")
    @ApiResponse(responseCode = "400", description = "Invalid request or a zero delta",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ValidationErrorsDto.class)))
    @ApiResponse(responseCode = "404", description = "Product not found (no stock item)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    @ApiResponse(responseCode = "409", description = "requestId already used with different payload, insufficient "
            + "stock, or the result exceeds the supported maximum",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    public ResponseEntity<StockAdjustmentDto> adjust(@Valid @RequestBody StockAdjustmentRequestDto request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(stockService.adjust(request));
    }

    @PutMapping("/items/{productId}")
    @Operation(summary = "Create a product's stock item",
            description = "Creates the item with ledgerVersion 1 and, for a positive quantity, an INITIAL_STOCK "
                    + "movement. An existing item is left unchanged and returned with 200.")
    @ApiResponse(responseCode = "201", description = "Stock item created")
    @ApiResponse(responseCode = "200", description = "Stock item already existed")
    @ApiResponse(responseCode = "400", description = "Invalid request",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ValidationErrorsDto.class)))
    public ResponseEntity<StockItemDto> createItem(@PathVariable Long productId,
                                                   @Valid @RequestBody InitialStockRequestDto request) {
        StockService.CreatedItem result = stockService.createItem(productId, request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.item());
    }

    @DeleteMapping("/items/{productId}")
    @Operation(summary = "Delete a product's stock",
            description = "Deletes the item, its movements, its reservation lines and its pending levels. "
                    + "Idempotent: 204 also when there is no item.")
    @ApiResponse(responseCode = "204", description = "Stock item deleted (or absent)")
    public ResponseEntity<Void> deleteItem(@PathVariable Long productId) {
        stockService.deleteItem(productId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/items/{productId}")
    @Operation(summary = "Get a product's stock", description = "Returns the quantity and ledger version.")
    @ApiResponse(responseCode = "200", description = "Stock item returned")
    @ApiResponse(responseCode = "404", description = "Product not found (no stock item)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    public StockItemDto item(@PathVariable Long productId) {
        return stockService.item(productId);
    }

    @GetMapping("/movements")
    @Operation(summary = "List a product's movements",
            description = "Movements newest first (createdAt desc, then id desc), in the backend's page format. "
                    + "Negative page is clamped to zero; size is clamped to 1-100. A product without a stock item "
                    + "has no movements.")
    @ApiResponse(responseCode = "200", description = "Movements returned")
    @ApiResponse(responseCode = "400", description = "Missing or invalid parameter",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ValidationErrorsDto.class)))
    public PageDto<InventoryMovementDto> movements(@RequestParam Long productId,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        return stockService.movements(productId, page, size);
    }
}
