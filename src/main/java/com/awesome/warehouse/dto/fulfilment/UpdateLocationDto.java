package com.awesome.warehouse.dto.fulfilment;

import jakarta.validation.constraints.NotNull;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Location activation")
public class UpdateLocationDto {
    @NotNull(message = "active is required")
    @Schema(description = "false stops new allocations to the location; parcels already there stay", example = "false")
    private Boolean active;
}
