package com.awesome.warehouse.controller.fulfilment;

import com.awesome.warehouse.dto.ErrorDto;
import com.awesome.warehouse.dto.tracking.PublicTrackingDto;
import com.awesome.warehouse.service.tracking.TrackingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Anonymous parcel tracking by tracking number (permitted without a token in WebSecurityConfig). */
@RestController
@RequiredArgsConstructor
@Tag(name = "Tracking", description = "Public parcel tracking")
public class PublicTrackingController {

    private final TrackingService trackingService;

    @GetMapping("/api/v1/tracking/{trackingNumber}")
    @Operation(summary = "Track parcel by tracking number", description = "Public, no authentication. Returns the carrier, "
            + "current status, estimated delivery and scans of the parcel; no order id, customer or address. "
            + "Surrounding spaces are ignored and letters are upper-cased.")
    @ApiResponse(responseCode = "200", description = "Tracking returned")
    @ApiResponse(responseCode = "404", description = "Tracking number not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    public PublicTrackingDto track(
            @Parameter(description = "Carrier tracking number", example = "1234567890") @PathVariable String trackingNumber) {
        return trackingService.trackPublic(trackingNumber);
    }
}
