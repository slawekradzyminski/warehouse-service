package com.awesome.warehouse.dto.fulfilment;

import com.awesome.warehouse.client.FulfilmentState;
import com.awesome.warehouse.entity.fulfilment.ShipmentStatus;
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
@Schema(description = "An order whose parcels' aggregate fulfilment state disagrees with the backend")
public class ReconciliationDto {
    @Schema(description = "Order ID", example = "42")
    private Long orderId;
    @Schema(types = {"string", "null"}, description = "fulfilmentState on the backend; null when the backend does not know the order",
            example = "AWAITING_PICK")
    private FulfilmentState backendState;
    @Schema(description = "State the backend should have: max(last mirrored, aggregate of the parcels)", example = "PICKED")
    private FulfilmentState aggregateState;
    @Schema(types = {"string", "null"}, description = "State last mirrored to the backend", example = "AWAITING_PICK")
    private FulfilmentState lastMirroredState;
    @Schema(description = "The order's parcels")
    private List<Parcel> parcels;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Parcel status in a reconciliation entry")
    public static class Parcel {
        @Schema(description = "Shipment ID", example = "7")
        private Long shipmentId;
        @Schema(description = "Location code", example = "KRK")
        private String locationCode;
        @Schema(description = "Parcel status", example = "PICKED")
        private ShipmentStatus status;
    }
}
