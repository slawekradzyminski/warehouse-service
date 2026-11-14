package com.awesome.warehouse.dto.fulfilment;

import jakarta.validation.constraints.Min;
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
@Schema(description = "Units of a product that could not be found while picking")
public class ShortPickDto {
    @NotNull(message = "Product ID is required")
    @Schema(description = "Product ID of a line in the parcel", example = "3")
    private Long productId;

    @NotNull(message = "Missing quantity is required")
    @Min(value = 1, message = "Missing quantity must be at least 1")
    @Schema(minimum = "1", description = "Units to move to a parcel at another location", example = "1")
    private Integer missingQuantity;
}
