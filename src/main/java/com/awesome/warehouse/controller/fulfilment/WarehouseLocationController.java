package com.awesome.warehouse.controller.fulfilment;

import com.awesome.warehouse.dto.ErrorDto;
import com.awesome.warehouse.dto.ValidationErrorsDto;
import com.awesome.warehouse.dto.fulfilment.ReconciliationDto;
import com.awesome.warehouse.dto.fulfilment.UpdateLocationDto;
import com.awesome.warehouse.dto.fulfilment.WarehouseLocationDto;
import com.awesome.warehouse.service.fulfilment.ParcelAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ROLE_WAREHOUSE', 'ROLE_ADMIN')")
@RequestMapping("/api/v1/warehouse")
@Tag(name = "Warehouse locations", description = "Warehouse locations, their activation and the fulfilment reconciliation report")
@SecurityRequirement(name = "bearerAuth")
@ApiResponse(responseCode = "401", description = "Unauthorized",
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
@ApiResponse(responseCode = "403", description = "Forbidden; requires ROLE_WAREHOUSE or ROLE_ADMIN",
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
public class WarehouseLocationController {

    private final ParcelAdminService parcelAdminService;

    @GetMapping("/locations")
    @Operation(summary = "List warehouse locations", description = "Lists every location by priority with its active flag and "
            + "the categories it stocks as PRIMARY or BACKUP (\"*\" stands for every category without its own entry).")
    @ApiResponse(responseCode = "200", description = "Locations returned")
    public List<WarehouseLocationDto> locations() {
        return parcelAdminService.locations();
    }

    @PutMapping("/locations/{code}")
    @Operation(summary = "Activate or deactivate location", description = "An inactive location receives no new allocations; "
            + "parcels already allocated there stay until they are reallocated.")
    @ApiResponse(responseCode = "200", description = "Location updated")
    @ApiResponse(responseCode = "400", description = "Missing active flag",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ValidationErrorsDto.class)))
    @ApiResponse(responseCode = "404", description = "Warehouse location not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    public WarehouseLocationDto update(@PathVariable String code, @Valid @RequestBody UpdateLocationDto request) {
        return parcelAdminService.updateLocation(code, request.getActive());
    }

    @GetMapping("/reconciliation")
    @Operation(summary = "Fulfilment reconciliation", description = "Orders (at most 100, lowest id first) with an open parcel "
            + "whose expected backend state, max(last mirrored, aggregate of the parcels), differs from the backend's "
            + "fulfilmentState, for example a customer cancellation the event feed has not applied yet.")
    @ApiResponse(responseCode = "200", description = "Disagreeing orders returned")
    @ApiResponse(responseCode = "503", description = "Backend unavailable",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    public List<ReconciliationDto> reconciliation() {
        return parcelAdminService.reconciliation();
    }
}
