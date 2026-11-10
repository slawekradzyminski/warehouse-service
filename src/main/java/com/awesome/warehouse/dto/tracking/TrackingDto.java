package com.awesome.warehouse.dto.tracking;

import com.awesome.warehouse.entity.fulfilment.Carrier;
import com.awesome.warehouse.entity.fulfilment.TrackingStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Carrier tracking of an order's shipment")
public class TrackingDto {
    @Schema(description = "Shipment ID", example = "1")
    private Long shipmentId;
    @Schema(description = "Order ID", example = "42")
    private Long orderId;
    @Schema(types = {"string", "null"}, description = "Carrier, set when shipped", example = "DHL")
    private Carrier carrier;
    @Schema(types = {"string", "null"}, description = "Carrier tracking number, set when shipped", example = "1234567890")
    private String trackingNumber;
    @Schema(description = "True when the parcel was handed to the simulated carrier, which advances it automatically", example = "true")
    private boolean simulated;
    @Schema(types = {"string", "null"}, description = "Status of the latest scan; null before the hand-over", example = "IN_TRANSIT")
    private TrackingStatus currentStatus;
    @Schema(types = {"string", "null"}, format = "local-date-time", description = "Expected delivery (the delivery time once delivered); null before the hand-over")
    private LocalDateTime estimatedDelivery;
    @Schema(description = "Carrier scans, oldest first")
    private List<TrackingEventDto> events;
}
