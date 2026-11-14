package com.awesome.warehouse.dto.stock;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/** Error of the stock API: the message plus, for reserve failures, the product that failed. */
@Schema(requiredProperties = {"message"})
public record StockErrorDto(
        @Schema(example = "Insufficient stock for product 1") String message,
        @JsonInclude(JsonInclude.Include.NON_NULL) @Schema(example = "1") Long productId) {
}
