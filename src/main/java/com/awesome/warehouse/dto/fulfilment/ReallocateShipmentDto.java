package com.awesome.warehouse.dto.fulfilment;

import jakarta.validation.constraints.Pattern;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Parcel reallocation. Omit targetLocation (or the whole body) to move the parcel to the best other "
        + "active location that stocks all of its categories")
public class ReallocateShipmentDto {
    @Pattern(regexp = "\\s*[A-Za-z0-9]{1,8}\\s*", message = "Target location must be a location code")
    @Schema(types = {"string", "null"}, description = "Target location code", example = "GDN")
    private String targetLocation;
}
