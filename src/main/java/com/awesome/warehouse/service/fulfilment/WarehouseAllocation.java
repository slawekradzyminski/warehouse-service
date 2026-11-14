package com.awesome.warehouse.service.fulfilment;

import com.awesome.warehouse.client.InternalOrderDto;
import com.awesome.warehouse.client.InternalOrderItemDto;
import com.awesome.warehouse.controller.exception.CustomException;
import com.awesome.warehouse.entity.fulfilment.LocationProductEntity;
import com.awesome.warehouse.entity.fulfilment.StockingRole;
import com.awesome.warehouse.entity.fulfilment.WarehouseLocationEntity;
import com.awesome.warehouse.repository.fulfilment.LocationProductRepository;
import com.awesome.warehouse.repository.fulfilment.WarehouseLocationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Loads the locations and their stocking from the database and runs {@link ShipmentAllocator}. */
@Component
@RequiredArgsConstructor
public class WarehouseAllocation {

    private final WarehouseLocationRepository locationRepository;
    private final LocationProductRepository stockingRepository;

    /** Every location (active or not) with its stocking roles, in rank order. */
    public List<ShipmentAllocator.Location> locations() {
        Map<String, Map<String, StockingRole>> roles = new HashMap<>();
        for (LocationProductEntity row : stockingRepository.findAll()) {
            String category = LocationProductEntity.ANY_CATEGORY.equals(row.getCategory())
                    ? LocationProductEntity.ANY_CATEGORY : ShipmentAllocator.key(row.getCategory());
            roles.computeIfAbsent(row.getLocationCode(), code -> new HashMap<>()).put(category, row.getRole());
        }
        return locationRepository.findAllByOrderByPriorityAscCodeAsc().stream()
                .map(location -> location(location, roles.getOrDefault(location.getCode(), Map.of())))
                .toList();
    }

    /**
     * Location code to the lines it ships, for the order's lines merged by product.
     *
     * @throws CustomException 409 when a line (or, for an order without lines, the order) has no active location
     */
    public Map<String, List<ShipmentAllocator.Line>> allocate(InternalOrderDto order) {
        Map<String, List<ShipmentAllocator.Line>> allocation = ShipmentAllocator.allocate(lines(order), locations());
        if (allocation.isEmpty()) {
            throw new CustomException("No active warehouse location", HttpStatus.CONFLICT);
        }
        return allocation;
    }

    /** The order's items merged by product (quantities summed), in first-appearance order. */
    public static List<ShipmentAllocator.Line> lines(InternalOrderDto order) {
        Map<Long, ShipmentAllocator.Line> byProduct = new LinkedHashMap<>();
        for (InternalOrderItemDto item : order.items()) {
            byProduct.merge(item.productId(),
                    new ShipmentAllocator.Line(item.productId(), item.productName(), item.category(), item.quantity()),
                    (left, right) -> new ShipmentAllocator.Line(left.productId(), left.productName(), left.category(),
                            left.quantity() + right.quantity()));
        }
        return List.copyOf(byProduct.values());
    }

    private static ShipmentAllocator.Location location(WarehouseLocationEntity location, Map<String, StockingRole> roles) {
        return new ShipmentAllocator.Location(location.getCode(), location.getPriority(), location.isActive(), roles);
    }

    /** Categories per role for the location view; {@code "*"} first, then alphabetical. */
    public Map<String, Map<StockingRole, List<String>>> stockingByLocation() {
        return stockingRepository.findAllByOrderByLocationCodeAscCategoryAsc().stream()
                .collect(Collectors.groupingBy(LocationProductEntity::getLocationCode,
                        Collectors.groupingBy(LocationProductEntity::getRole,
                                Collectors.mapping(LocationProductEntity::getCategory, Collectors.toList()))));
    }
}
