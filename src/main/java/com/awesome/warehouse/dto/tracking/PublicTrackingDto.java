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

/** Anonymous tracking view: no order id, owner or address. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Public carrier tracking of a parcel; contains no order or customer data")
public class PublicTrackingDto {
    @Schema(description = "Shipment ID", example = "1")
    private Long shipmentId;
    @Schema(description = "Carrier", example = "DHL")
    private Carrier carrier;
    @Schema(description = "Carrier tracking number", example = "1234567890")
    private String trackingNumber;
    @Schema(description = "True when the parcel was handed to the simulated carrier", example = "true")
    private boolean simulated;
    @Schema(types = {"string", "null"}, description = "Status of the latest scan", example = "OUT_FOR_DELIVERY")
    private TrackingStatus currentStatus;
    @Schema(types = {"string", "null"}, format = "local-date-time", description = "Expected delivery (the delivery time once delivered)")
    private LocalDateTime estimatedDelivery;
    @Schema(description = "Carrier scans, oldest first")
    private List<TrackingEventDto> events;

    public static PublicTrackingDto from(TrackingDto tracking) {
        return PublicTrackingDto.builder()
                .shipmentId(tracking.getShipmentId())
                .carrier(tracking.getCarrier())
                .trackingNumber(tracking.getTrackingNumber())
                .simulated(tracking.isSimulated())
                .currentStatus(tracking.getCurrentStatus())
                .estimatedDelivery(tracking.getEstimatedDelivery())
                .events(tracking.getEvents())
                .build();
    }
}
