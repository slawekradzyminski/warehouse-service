package com.awesome.warehouse.service.fulfilment;

import com.awesome.warehouse.client.BackendClient;
import com.awesome.warehouse.client.FulfilmentState;
import com.awesome.warehouse.client.InternalOrderDto;
import com.awesome.warehouse.controller.exception.CustomException;
import com.awesome.warehouse.dto.fulfilment.ReconciliationDto;
import com.awesome.warehouse.dto.fulfilment.ShipmentDto;
import com.awesome.warehouse.dto.fulfilment.WarehouseLocationDto;
import com.awesome.warehouse.entity.fulfilment.ShipmentEntity;
import com.awesome.warehouse.entity.fulfilment.ShipmentLineEntity;
import com.awesome.warehouse.entity.fulfilment.ShipmentStatus;
import com.awesome.warehouse.entity.fulfilment.StockingRole;
import com.awesome.warehouse.entity.fulfilment.WarehouseLocationEntity;
import com.awesome.warehouse.repository.fulfilment.ShipmentLineRepository;
import com.awesome.warehouse.repository.fulfilment.ShipmentRepository;
import com.awesome.warehouse.repository.fulfilment.WarehouseLocationRepository;
import com.awesome.warehouse.repository.returns.OrderLockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Operator tools for locations and parcels: location activation, reallocating a parcel to
 * another location, short picks and the fulfilment reconciliation report.
 *
 * <p>Reallocation and short picks only touch AWAITING_PICK parcels and only ever move lines
 * into an AWAITING_PICK parcel, so the order's aggregate never moves forward and the
 * backend is not called. Both run under the order lock (like every fulfilment write; see
 * {@link ShipmentService}), after filling the lines of a pre-1.5.0 parcel outside the
 * transaction.
 */
@Service
@RequiredArgsConstructor
public class ParcelAdminService {

    private static final Set<ShipmentStatus> OPEN = EnumSet.of(ShipmentStatus.AWAITING_PICK, ShipmentStatus.PICKED,
            ShipmentStatus.PACKED, ShipmentStatus.SHIPPED);

    private final ShipmentService shipmentService;
    private final ShipmentRepository shipmentRepository;
    private final ShipmentLineRepository lineRepository;
    private final WarehouseLocationRepository locationRepository;
    private final OrderLockRepository orderLockRepository;
    private final WarehouseAllocation allocation;
    private final LegacyShipmentLines legacyLines;
    private final ShipmentViews views;
    private final BackendClient backendClient;
    private final TransactionTemplate transaction;

    @Transactional(readOnly = true)
    public List<WarehouseLocationDto> locations() {
        Map<String, Map<StockingRole, List<String>>> stocking = allocation.stockingByLocation();
        return locationRepository.findAllByOrderByPriorityAscCodeAsc().stream()
                .map(location -> toDto(location, stocking.getOrDefault(location.getCode(), Map.of())))
                .toList();
    }

    /** Activates or deactivates a location. Parcels already allocated there stay; see reallocate. */
    @Transactional
    public WarehouseLocationDto updateLocation(String rawCode, boolean active) {
        WarehouseLocationEntity location = locationRepository.findById(code(rawCode))
                .orElseThrow(ParcelAdminService::locationNotFound);
        location.setActive(active);
        locationRepository.saveAndFlush(location);
        return toDto(location, allocation.stockingByLocation().getOrDefault(location.getCode(), Map.of()));
    }

    /**
     * Moves an AWAITING_PICK parcel to {@code rawTarget}, or without a target to the best other
     * active location that stocks all of its categories (preferring one that stocks them all
     * as PRIMARY, then one where the order already has an AWAITING_PICK parcel, then
     * priority). The lines merge into the order's parcel at the target when there is one
     * (the moved parcel is then removed); otherwise the parcel itself changes location.
     *
     * @return the parcel now holding the lines
     */
    public ShipmentDto reallocate(Long id, String rawTarget, String actor) {
        prepare(id);
        return transaction.execute(status -> {
            ShipmentService.LockedOrder order = shipmentService.lockOrderOf(id);
            ShipmentEntity parcel = order.parcel();
            ShipmentService.require(parcel, "reallocate", ShipmentStatus.AWAITING_PICK);
            List<ShipmentLineEntity> lines = lines(parcel);
            Set<String> categories = lines.stream().map(ShipmentLineEntity::getCategory)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            List<ShipmentAllocator.Location> locations = allocation.locations();
            Map<String, ShipmentEntity> byLocation = byLocation(order.parcels());
            String target = rawTarget == null || rawTarget.isBlank()
                    ? ShipmentAllocator.alternative(categories, parcel.getLocationCode(), locations,
                                    code -> acceptsLines(byLocation.get(code)), byLocation::containsKey)
                            .orElseThrow(() -> new CustomException(
                                    "No other active warehouse location can take this parcel", HttpStatus.CONFLICT))
                    : checkedTarget(code(rawTarget), parcel, categories, locations, byLocation);
            ShipmentEntity existing = byLocation.get(target);
            if (existing == null) {
                parcel.setLocationCode(target);
                parcel.setLastActor(actor);
                return views.toDto(shipmentRepository.saveAndFlush(parcel));
            }
            for (ShipmentLineEntity line : lines) {
                addUnits(existing, line, line.getQuantity());
                lineRepository.delete(line);
            }
            lineRepository.flush();
            shipmentRepository.delete(parcel);
            shipmentRepository.flush();
            existing.setLastActor(actor);
            return views.toDto(shipmentRepository.saveAndFlush(existing));
        });
    }

    /**
     * Records that {@code missingQuantity} units of a product could not be picked for an
     * AWAITING_PICK parcel and moves them to the order's AWAITING_PICK parcel at another
     * active location that stocks the product's category (a new parcel when the order has
     * none there). A parcel left without lines is removed.
     *
     * @return the parcel that received the units
     */
    public ShipmentDto shortPick(Long id, Long productId, int missingQuantity, String actor) {
        prepare(id);
        return transaction.execute(status -> {
            ShipmentService.LockedOrder order = shipmentService.lockOrderOf(id);
            ShipmentEntity parcel = order.parcel();
            ShipmentService.require(parcel, "short-pick", ShipmentStatus.AWAITING_PICK);
            List<ShipmentLineEntity> lines = lines(parcel);
            ShipmentLineEntity line = lines.stream().filter(l -> l.getProductId().equals(productId)).findFirst()
                    .orElseThrow(() -> new CustomException("Product is not in this shipment", HttpStatus.NOT_FOUND));
            if (missingQuantity > line.getQuantity()) {
                throw new CustomException("Missing quantity exceeds the " + line.getQuantity()
                        + " units of the product in this shipment", HttpStatus.CONFLICT);
            }
            Map<String, ShipmentEntity> byLocation = byLocation(order.parcels());
            String target = ShipmentAllocator.alternative(List.of(nullToEmpty(line.getCategory())),
                            parcel.getLocationCode(), allocation.locations(),
                            code -> acceptsLines(byLocation.get(code)), byLocation::containsKey)
                    .orElseThrow(() -> new CustomException("No other active warehouse location stocks "
                            + line.getCategory(), HttpStatus.CONFLICT));
            ShipmentEntity receiver = byLocation.get(target);
            if (receiver == null) {
                receiver = shipmentRepository.saveAndFlush(ShipmentEntity.builder()
                        .orderId(parcel.getOrderId())
                        .username(parcel.getUsername())
                        .locationCode(target)
                        .totalUnits(0)
                        .status(ShipmentStatus.AWAITING_PICK)
                        .createdBy(actor)
                        .lastActor(actor)
                        .build());
            }
            addUnits(receiver, line, missingQuantity);
            receiver.setLastActor(actor);
            line.setQuantity(line.getQuantity() - missingQuantity);
            parcel.setTotalUnits(parcel.getTotalUnits() - missingQuantity);
            parcel.setLastActor(actor);
            if (line.getQuantity() == 0) {
                lineRepository.delete(line);
            }
            lineRepository.flush();
            if (parcel.getTotalUnits() == 0) {
                shipmentRepository.delete(parcel);
            }
            shipmentRepository.flush();
            return views.toDto(shipmentRepository.saveAndFlush(receiver));
        });
    }

    /**
     * Orders with an open parcel whose expected backend state ({@code max(last mirrored,
     * aggregate)}) differs from the backend's {@code fulfilmentState}: at most 100 orders,
     * lowest order id first. Read-only; the backend is read outside any transaction.
     */
    public List<ReconciliationDto> reconciliation() {
        List<Long> orderIds = shipmentRepository.findOrderIdsWithStatusIn(OPEN, PageRequest.of(0, 100));
        if (orderIds.isEmpty()) {
            return List.of();
        }
        Map<Long, FulfilmentState> backendStates = backendClient.getOrders(orderIds).stream()
                .collect(Collectors.toMap(InternalOrderDto::id, InternalOrderDto::fulfilmentState, (a, b) -> a));
        Map<Long, List<ShipmentEntity>> parcels = shipmentRepository.findByOrderIdInOrderByIdAsc(orderIds).stream()
                .collect(Collectors.groupingBy(ShipmentEntity::getOrderId));
        Map<Long, FulfilmentState> mirrored = new java.util.HashMap<>();
        orderLockRepository.findAllById(orderIds).forEach(lock -> mirrored.put(lock.getOrderId(), lock.getMirroredState()));
        List<ReconciliationDto> report = new ArrayList<>();
        for (Long orderId : orderIds) {
            List<ShipmentEntity> orderParcels = parcels.getOrDefault(orderId, List.of());
            FulfilmentState expected = FulfilmentAggregate.target(mirrored.get(orderId),
                    FulfilmentAggregate.aggregate(orderParcels.stream().map(ShipmentEntity::getStatus).toList()));
            FulfilmentState backend = backendStates.get(orderId);
            if (backend != expected) {
                report.add(ReconciliationDto.builder()
                        .orderId(orderId)
                        .backendState(backend)
                        .aggregateState(expected)
                        .lastMirroredState(mirrored.get(orderId))
                        .parcels(orderParcels.stream().map(p -> ReconciliationDto.Parcel.builder()
                                .shipmentId(p.getId()).locationCode(p.getLocationCode()).status(p.getStatus()).build())
                                .toList())
                        .build());
            }
        }
        return report;
    }

    /** 404 for unknown parcels, then fills a pre-1.5.0 parcel's lines (outside any transaction). */
    private void prepare(Long id) {
        ShipmentEntity parcel = shipmentRepository.findById(id).orElseThrow(ShipmentService::shipmentNotFound);
        legacyLines.fill(List.of(parcel));
    }

    private List<ShipmentLineEntity> lines(ShipmentEntity parcel) {
        if (parcel.isLinesPending()) {
            throw new CustomException("Backend unavailable", HttpStatus.SERVICE_UNAVAILABLE);
        }
        return lineRepository.findByShipmentIdOrderByIdAsc(parcel.getId());
    }

    private String checkedTarget(String target, ShipmentEntity parcel, Set<String> categories,
                                 List<ShipmentAllocator.Location> locations, Map<String, ShipmentEntity> byLocation) {
        ShipmentAllocator.Location location = locations.stream().filter(l -> l.code().equals(target)).findFirst()
                .orElseThrow(ParcelAdminService::locationNotFound);
        if (target.equals(parcel.getLocationCode())) {
            throw new CustomException("Shipment is already at " + target, HttpStatus.CONFLICT);
        }
        if (!location.active()) {
            throw new CustomException("Warehouse location " + target + " is not active", HttpStatus.CONFLICT);
        }
        Optional<String> unstocked = categories.stream().filter(category -> !location.stocks(category)).findFirst();
        if (unstocked.isPresent()) {
            throw new CustomException("Warehouse location " + target + " does not stock " + unstocked.get(),
                    HttpStatus.CONFLICT);
        }
        ShipmentEntity existing = byLocation.get(target);
        if (!acceptsLines(existing)) {
            throw new CustomException("The order's parcel at " + target + " is already " + existing.getStatus(),
                    HttpStatus.CONFLICT);
        }
        return target;
    }

    /** Lines can go to a location without a parcel of the order, or to its AWAITING_PICK parcel there. */
    private static boolean acceptsLines(ShipmentEntity parcelAtLocation) {
        return parcelAtLocation == null || parcelAtLocation.getStatus() == ShipmentStatus.AWAITING_PICK;
    }

    private void addUnits(ShipmentEntity receiver, ShipmentLineEntity line, int quantity) {
        ShipmentLineEntity target = lineRepository.findByShipmentIdOrderByIdAsc(receiver.getId()).stream()
                .filter(l -> l.getProductId().equals(line.getProductId()))
                .findFirst()
                .orElseGet(() -> ShipmentLineEntity.builder()
                        .shipmentId(receiver.getId())
                        .productId(line.getProductId())
                        .productName(line.getProductName())
                        .category(line.getCategory())
                        .quantity(0)
                        .build());
        target.setQuantity(target.getQuantity() + quantity);
        lineRepository.save(target);
        receiver.setTotalUnits(receiver.getTotalUnits() + quantity);
    }

    private static Map<String, ShipmentEntity> byLocation(List<ShipmentEntity> parcels) {
        return parcels.stream().collect(Collectors.toMap(ShipmentEntity::getLocationCode, Function.identity()));
    }

    private static WarehouseLocationDto toDto(WarehouseLocationEntity location, Map<StockingRole, List<String>> stocking) {
        return WarehouseLocationDto.builder()
                .code(location.getCode())
                .name(location.getName())
                .city(location.getCity())
                .active(location.isActive())
                .priority(location.getPriority())
                .primaryCategories(stocking.getOrDefault(StockingRole.PRIMARY, List.of()))
                .backupCategories(stocking.getOrDefault(StockingRole.BACKUP, List.of()))
                .build();
    }

    private static String code(String raw) {
        return raw.trim().toUpperCase(Locale.ROOT);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static CustomException locationNotFound() {
        return new CustomException("Warehouse location not found", HttpStatus.NOT_FOUND);
    }
}
