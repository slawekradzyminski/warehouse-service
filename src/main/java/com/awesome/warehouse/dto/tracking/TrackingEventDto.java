package com.awesome.warehouse.dto.tracking;

import com.awesome.warehouse.entity.fulfilment.TrackingStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "One carrier scan")
public class TrackingEventDto {
    @Schema(description = "Carrier status after this scan", example = "IN_TRANSIT")
    private TrackingStatus status;
    @Schema(description = "Where the parcel was scanned", example = "DHL Hub, Leipzig")
    private String location;
    @Schema(description = "What happened", example = "Collected from the sender and sorted at the hub")
    private String description;
    @Schema(type = "string", format = "local-date-time", description = "When the scan happened")
    private LocalDateTime occurredAt;
}
