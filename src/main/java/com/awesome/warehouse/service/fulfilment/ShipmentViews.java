package com.awesome.warehouse.service.fulfilment;

import com.awesome.warehouse.dto.fulfilment.ShipmentDto;
import com.awesome.warehouse.dto.fulfilment.ShipmentLineDto;
import com.awesome.warehouse.entity.fulfilment.ShipmentEntity;
import com.awesome.warehouse.entity.fulfilment.ShipmentLineEntity;
import com.awesome.warehouse.entity.fulfilment.WarehouseLocationEntity;
import com.awesome.warehouse.repository.fulfilment.ShipmentLineRepository;
import com.awesome.warehouse.repository.fulfilment.ShipmentRepository;
import com.awesome.warehouse.repository.fulfilment.WarehouseLocationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Maps parcels to their public view: location, position among the order's parcels and
 * lines. Three queries per call, whatever the number of parcels (siblings, lines,
 * locations). Inside a transaction the queries flush pending changes first, so the view
 * shows them.
 */
@Component
@RequiredArgsConstructor
public class ShipmentViews {

    private final ShipmentRepository shipmentRepository;
    private final ShipmentLineRepository lineRepository;
    private final WarehouseLocationRepository locationRepository;

    public ShipmentDto toDto(ShipmentEntity parcel) {
        return toDtos(List.of(parcel)).getFirst();
    }

    public List<ShipmentDto> toDtos(Collection<ShipmentEntity> parcels) {
        if (parcels.isEmpty()) {
            return List.of();
        }
        Map<Long, List<Long>> siblings = shipmentRepository
                .findByOrderIdInOrderByIdAsc(parcels.stream().map(ShipmentEntity::getOrderId).distinct().toList())
                .stream()
                .collect(Collectors.groupingBy(ShipmentEntity::getOrderId,
                        Collectors.mapping(ShipmentEntity::getId, Collectors.toList())));
        Map<Long, List<ShipmentLineEntity>> lines = lineRepository
                .findByShipmentIdInOrderByIdAsc(parcels.stream().map(ShipmentEntity::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(ShipmentLineEntity::getShipmentId));
        Map<String, String> locationNames = locationRepository.findAll().stream()
                .collect(Collectors.toMap(WarehouseLocationEntity::getCode, WarehouseLocationEntity::getName,
                        (left, right) -> left));
        return parcels.stream()
                .map(parcel -> toDto(parcel, siblings.getOrDefault(parcel.getOrderId(), List.of(parcel.getId())),
                        lines.getOrDefault(parcel.getId(), List.of()), locationNames::get))
                .toList();
    }

    private static ShipmentDto toDto(ShipmentEntity shipment, List<Long> siblings, List<ShipmentLineEntity> lines,
                                     Function<String, String> locationName) {
        int index = siblings.indexOf(shipment.getId());
        return ShipmentDto.builder()
                .id(shipment.getId())
                .orderId(shipment.getOrderId())
                .username(shipment.getUsername())
                .status(shipment.getStatus())
                .totalUnits(shipment.getTotalUnits())
                .locationCode(shipment.getLocationCode())
                .locationName(locationName.apply(shipment.getLocationCode()))
                .parcelIndex(index < 0 ? 1 : index + 1)
                .parcelCount(Math.max(siblings.size(), 1))
                .lines(lines.stream().map(ShipmentViews::line).toList())
                .carrier(shipment.getCarrier())
                .trackingNumber(shipment.getTrackingNumber())
                .weightKg(shipment.getWeightKg())
                .createdBy(shipment.getCreatedBy())
                .lastActor(shipment.getLastActor())
                .createdAt(shipment.getCreatedAt())
                .updatedAt(shipment.getUpdatedAt())
                .pickedAt(shipment.getPickedAt())
                .packedAt(shipment.getPackedAt())
                .shippedAt(shipment.getShippedAt())
                .deliveredAt(shipment.getDeliveredAt())
                .cancelledAt(shipment.getCancelledAt())
                .build();
    }

    private static ShipmentLineDto line(ShipmentLineEntity line) {
        return ShipmentLineDto.builder()
                .productId(line.getProductId())
                .productName(line.getProductName())
                .category(line.getCategory())
                .quantity(line.getQuantity())
                .build();
    }
}
