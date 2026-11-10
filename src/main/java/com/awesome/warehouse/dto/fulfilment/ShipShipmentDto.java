package com.awesome.warehouse.dto.fulfilment;

import com.awesome.warehouse.entity.fulfilment.Carrier;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Carrier hand-over. INPOST: 24 digits, DPD: 14 digits, DHL: 10 digits, UPS: 1Z + 16 letters or digits. "
        + "Omit trackingNumber to hand the parcel to the simulated carrier, which generates the number and delivers automatically.")
public class ShipShipmentDto {
    @NotNull(message = "Carrier is required")
    @Schema(description = "Carrier", example = "DHL")
    private Carrier carrier;

    @Pattern(regexp = "(?s).*\\S.*", message = "Tracking number must not be blank")
    @Schema(types = {"string", "null"}, minLength = 1, pattern = "\\S",
            description = "Tracking number; surrounding spaces are ignored and letters are upper-cased. "
                    + "Omit it (or send null) for the simulated carrier.", example = "1234567890")
    private String trackingNumber;
}
