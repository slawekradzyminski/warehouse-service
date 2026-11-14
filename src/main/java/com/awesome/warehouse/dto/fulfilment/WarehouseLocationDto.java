package com.awesome.warehouse.dto.fulfilment;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Warehouse location with the categories it stocks")
public class WarehouseLocationDto {
    @Schema(description = "Location code", example = "KRK")
    private String code;
    @Schema(description = "Location name", example = "Krakow Central")
    private String name;
    @Schema(description = "City", example = "Krakow")
    private String city;
    @Schema(description = "Whether new parcels are allocated to this location", example = "true")
    private boolean active;
    @Schema(description = "Allocation priority; lower numbers are preferred", example = "1")
    private int priority;
    @Schema(description = "Categories shipped from here first; \"*\" stands for every category without its own entry",
            example = "[\"*\"]")
    private List<String> primaryCategories;
    @Schema(description = "Categories shipped from here when no primary location can; \"*\" as above",
            example = "[\"Books\"]")
    private List<String> backupCategories;
}
