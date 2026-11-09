package com.awesome.warehouse.service.purchasing;

import com.awesome.warehouse.client.BackendClient;
import com.awesome.warehouse.client.InternalProductDto;
import com.awesome.warehouse.client.InventoryReceiptRequest;
import com.awesome.warehouse.client.ReceiptLine;
import com.awesome.warehouse.client.ReceiptType;
import com.awesome.warehouse.controller.exception.CustomException;
import com.awesome.warehouse.dto.purchasing.PurchaseOrderDto;
import com.awesome.warehouse.dto.purchasing.PurchaseOrderLineDto;
import com.awesome.warehouse.dto.purchasing.PurchaseOrderLineRequestDto;
import com.awesome.warehouse.dto.purchasing.PurchaseOrderRequestDto;
import com.awesome.warehouse.dto.purchasing.ReceiptLineDto;
import com.awesome.warehouse.dto.purchasing.ReceiptRequestDto;
import com.awesome.warehouse.dto.purchasing.ReorderSuggestionDto;
import com.awesome.warehouse.dto.purchasing.SupplierDto;
import com.awesome.warehouse.dto.purchasing.SupplierRequestDto;
import com.awesome.warehouse.entity.purchasing.PurchaseOrderEntity;
import com.awesome.warehouse.entity.purchasing.PurchaseOrderLineEntity;
import com.awesome.warehouse.entity.purchasing.PurchaseOrderStatus;
import com.awesome.warehouse.entity.purchasing.PurchaseReceiptEntity;
import com.awesome.warehouse.entity.purchasing.SupplierEntity;
import com.awesome.warehouse.repository.purchasing.PurchaseOrderRepository;
import com.awesome.warehouse.repository.purchasing.PurchaseReceiptRepository;
import com.awesome.warehouse.repository.purchasing.SupplierRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PurchasingService {

    static final int REORDER_MULTIPLIER = 4;
    private static final int MIN_THRESHOLD = 1;
    private static final Set<PurchaseOrderStatus> INCOMING =
            Set.of(PurchaseOrderStatus.SUBMITTED, PurchaseOrderStatus.PARTIALLY_RECEIVED);

    private final SupplierRepository supplierRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseReceiptRepository receiptRepository;
    private final BackendClient backendClient;
    private final Clock warehouseClock;

    @Transactional(readOnly = true)
    public List<SupplierDto> suppliers() {
        return supplierRepository.findAll(Sort.by("name")).stream().map(PurchasingService::toDto).toList();
    }

    @Transactional
    public SupplierDto createSupplier(SupplierRequestDto request) {
        String name = request.getName().trim();
        if (supplierRepository.existsByNameIgnoreCase(name)) {
            throw new CustomException("Supplier name already exists", HttpStatus.CONFLICT);
        }
        return toDto(supplierRepository.saveAndFlush(SupplierEntity.builder()
                .name(name)
                .email(request.getEmail().trim())
                .leadTimeDays(request.getLeadTimeDays())
                .build()));
    }

    @Transactional(readOnly = true)
    public Page<PurchaseOrderDto> list(PurchaseOrderStatus status, Pageable pageable) {
        Page<PurchaseOrderEntity> page = status == null
                ? purchaseOrderRepository.findAllBy(pageable)
                : purchaseOrderRepository.findByStatus(status, pageable);
        return page.map(PurchasingService::toDto);
    }

    @Transactional(readOnly = true)
    public PurchaseOrderDto get(Long id) {
        return toDto(purchaseOrderRepository.findById(id).orElseThrow(PurchasingService::notFound));
    }

    @Transactional
    public PurchaseOrderDto create(PurchaseOrderRequestDto request, String actor) {
        Draft draft = resolveDraft(request);
        PurchaseOrderEntity purchaseOrder = PurchaseOrderEntity.builder()
                .status(PurchaseOrderStatus.DRAFT)
                .createdBy(actor)
                .build();
        applyDraft(draft, purchaseOrder);
        return toDto(purchaseOrderRepository.saveAndFlush(purchaseOrder));
    }

    @Transactional
    public PurchaseOrderDto update(Long id, PurchaseOrderRequestDto request) {
        PurchaseOrderEntity purchaseOrder = lock(id);
        if (purchaseOrder.getStatus() != PurchaseOrderStatus.DRAFT) {
            throw new CustomException("Only draft purchase orders can be edited", HttpStatus.CONFLICT);
        }
        Draft draft = resolveDraft(request);
        purchaseOrder.getLines().clear();
        purchaseOrderRepository.flush();
        applyDraft(draft, purchaseOrder);
        return toDto(purchaseOrderRepository.saveAndFlush(purchaseOrder));
    }

    @Transactional
    public PurchaseOrderDto submit(Long id) {
        PurchaseOrderEntity purchaseOrder = lock(id);
        if (purchaseOrder.getStatus() != PurchaseOrderStatus.DRAFT) {
            throw new CustomException("Only draft purchase orders can be submitted", HttpStatus.CONFLICT);
        }
        if (purchaseOrder.getLines().isEmpty()) {
            throw new CustomException("A purchase order needs at least one line", HttpStatus.BAD_REQUEST);
        }
        LocalDateTime now = LocalDateTime.now(warehouseClock);
        purchaseOrder.setStatus(PurchaseOrderStatus.SUBMITTED);
        purchaseOrder.setSubmittedAt(now);
        purchaseOrder.setExpectedAt(now.plusDays(purchaseOrder.getSupplier().getLeadTimeDays()));
        return toDto(purchaseOrderRepository.saveAndFlush(purchaseOrder));
    }

    @Transactional
    public PurchaseOrderDto cancel(Long id) {
        PurchaseOrderEntity purchaseOrder = lock(id);
        if (purchaseOrder.getStatus() != PurchaseOrderStatus.DRAFT
                && purchaseOrder.getStatus() != PurchaseOrderStatus.SUBMITTED) {
            throw new CustomException("Only draft or submitted purchase orders can be cancelled", HttpStatus.CONFLICT);
        }
        purchaseOrder.setStatus(PurchaseOrderStatus.CANCELLED);
        purchaseOrder.setCancelledAt(LocalDateTime.now(warehouseClock));
        return toDto(purchaseOrderRepository.saveAndFlush(purchaseOrder));
    }

    /**
     * Books received goods. The purchase order row is locked, the receipt is
     * validated locally, the backend raises stock (idempotent per requestId)
     * and only then the received quantities and the receipt are stored.
     */
    @Transactional
    public PurchaseOrderDto receive(Long id, ReceiptRequestDto request, String actor) {
        PurchaseOrderEntity purchaseOrder = lock(id);
        Map<Long, Integer> quantities = new TreeMap<>();
        for (ReceiptLineDto line : request.getLines()) {
            if (quantities.put(line.getProductId(), line.getQuantity()) != null) {
                throw new CustomException(
                        "Product " + line.getProductId() + " is listed more than once", HttpStatus.BAD_REQUEST);
            }
        }
        String fingerprint = fingerprint(quantities);
        Optional<PurchaseReceiptEntity> previous = receiptRepository.findByPurchaseOrderIdAndRequestId(id, request.getRequestId());
        if (previous.isPresent()) {
            if (!previous.get().getLinesFingerprint().equals(fingerprint)) {
                throw new CustomException("requestId already used with different lines", HttpStatus.CONFLICT);
            }
            return toDto(purchaseOrder);
        }
        if (!purchaseOrder.getStatus().isOpenForReceipt()) {
            throw new CustomException(
                    "Cannot receive goods for a purchase order in status " + purchaseOrder.getStatus(), HttpStatus.CONFLICT);
        }
        Map<Long, PurchaseOrderLineEntity> lines = purchaseOrder.getLines().stream()
                .collect(Collectors.toMap(PurchaseOrderLineEntity::getProductId, Function.identity()));
        for (Map.Entry<Long, Integer> entry : quantities.entrySet()) {
            PurchaseOrderLineEntity line = lines.get(entry.getKey());
            if (line == null) {
                throw new CustomException(
                        "Product " + entry.getKey() + " is not on this purchase order", HttpStatus.BAD_REQUEST);
            }
            if (entry.getValue() > line.outstandingQuantity()) {
                throw new CustomException("Receiving " + entry.getValue() + " units of product " + entry.getKey()
                        + " exceeds the outstanding quantity of " + line.outstandingQuantity(), HttpStatus.CONFLICT);
            }
        }
        backendClient.receiveInventory(new InventoryReceiptRequest(
                backendRequestId(purchaseOrder.getId(), request.getRequestId()), ReceiptType.PURCHASE_RECEIVED, null,
                "Purchase order #" + purchaseOrder.getId(),
                quantities.entrySet().stream().map(e -> new ReceiptLine(e.getKey(), e.getValue())).toList()));
        quantities.forEach((productId, quantity) -> {
            PurchaseOrderLineEntity line = lines.get(productId);
            line.setReceivedQuantity(line.getReceivedQuantity() + quantity);
        });
        boolean complete = purchaseOrder.getLines().stream().allMatch(line -> line.outstandingQuantity() == 0);
        purchaseOrder.setStatus(complete ? PurchaseOrderStatus.RECEIVED : PurchaseOrderStatus.PARTIALLY_RECEIVED);
        if (complete) {
            purchaseOrder.setReceivedAt(LocalDateTime.now(warehouseClock));
        }
        receiptRepository.save(PurchaseReceiptEntity.builder()
                .purchaseOrder(purchaseOrder)
                .requestId(request.getRequestId())
                .linesFingerprint(fingerprint)
                .receivedBy(actor)
                .build());
        return toDto(purchaseOrderRepository.saveAndFlush(purchaseOrder));
    }

    /**
     * The backend treats receipt requestIds as globally unique, while clients
     * only need them unique per purchase order. Deriving the backend id from
     * both keeps a reused client requestId on another purchase order from
     * being replayed as the earlier receipt.
     */
    static UUID backendRequestId(Long purchaseOrderId, UUID requestId) {
        return UUID.nameUUIDFromBytes(("purchase-order-" + purchaseOrderId + "-" + requestId)
                .getBytes(StandardCharsets.UTF_8));
    }

    @Transactional(readOnly = true)
    public List<ReorderSuggestionDto> reorderSuggestions(int threshold) {
        if (threshold < MIN_THRESHOLD) {
            throw new IllegalArgumentException("lowStockThreshold must be at least 1");
        }
        Map<Long, Integer> incoming = new HashMap<>();
        for (Object[] row : purchaseOrderRepository.sumOutstandingByProduct(INCOMING)) {
            incoming.put((Long) row[0], ((Number) row[1]).intValue());
        }
        return backendClient.getAllProducts().stream()
                .map(product -> suggestion(product, incoming.getOrDefault(product.id(), 0), threshold))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt((ReorderSuggestionDto s) -> s.getStockQuantity() + s.getIncomingQuantity())
                        .thenComparing(ReorderSuggestionDto::getProductId))
                .toList();
    }

    static ReorderSuggestionDto suggestion(InternalProductDto product, int incoming, int threshold) {
        int projected = product.stockQuantity() + incoming;
        if (projected > threshold) {
            return null;
        }
        return ReorderSuggestionDto.builder()
                .productId(product.id())
                .productName(product.name())
                .category(product.category())
                .stockQuantity(product.stockQuantity())
                .incomingQuantity(incoming)
                .suggestedQuantity(REORDER_MULTIPLIER * threshold - projected)
                .build();
    }

    /** Supplier and product snapshots for a draft, resolved before anything local changes. */
    private record Draft(SupplierEntity supplier, String note, List<PurchaseOrderLineRequestDto> lines,
                         Map<Long, InternalProductDto> products) {
    }

    private Draft resolveDraft(PurchaseOrderRequestDto request) {
        SupplierEntity supplier = supplierRepository.findById(request.getSupplierId())
                .orElseThrow(() -> new CustomException("Supplier not found", HttpStatus.NOT_FOUND));
        List<PurchaseOrderLineRequestDto> lines = request.getLines() == null ? List.of() : request.getLines();
        Set<Long> productIds = new LinkedHashSet<>();
        for (PurchaseOrderLineRequestDto line : lines) {
            if (!productIds.add(line.getProductId())) {
                throw new CustomException(
                        "Product " + line.getProductId() + " is listed more than once", HttpStatus.BAD_REQUEST);
            }
        }
        Map<Long, InternalProductDto> products = backendClient.getProducts(productIds).stream()
                .collect(Collectors.toMap(InternalProductDto::id, Function.identity(), (a, b) -> a));
        if (!products.keySet().containsAll(productIds)) {
            throw new CustomException("Product not found", HttpStatus.NOT_FOUND);
        }
        String note = request.getNote() == null || request.getNote().isBlank() ? null : request.getNote().trim();
        return new Draft(supplier, note, lines, products);
    }

    private static void applyDraft(Draft draft, PurchaseOrderEntity purchaseOrder) {
        purchaseOrder.setSupplier(draft.supplier());
        purchaseOrder.setNote(draft.note());
        for (PurchaseOrderLineRequestDto line : draft.lines()) {
            purchaseOrder.addLine(PurchaseOrderLineEntity.builder()
                    .productId(line.getProductId())
                    .productName(draft.products().get(line.getProductId()).name())
                    .orderedQuantity(line.getQuantity())
                    .receivedQuantity(0)
                    .unitCost(line.getUnitCost())
                    .build());
        }
    }

    static String fingerprint(Map<Long, Integer> quantities) {
        String canonical = quantities.entrySet().stream()
                .map(entry -> entry.getKey() + ":" + entry.getValue())
                .collect(Collectors.joining(","));
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private PurchaseOrderEntity lock(Long id) {
        return purchaseOrderRepository.findByIdForUpdate(id).orElseThrow(PurchasingService::notFound);
    }

    private static CustomException notFound() {
        return new CustomException("Purchase order not found", HttpStatus.NOT_FOUND);
    }

    private static SupplierDto toDto(SupplierEntity supplier) {
        return SupplierDto.builder()
                .id(supplier.getId())
                .name(supplier.getName())
                .email(supplier.getEmail())
                .leadTimeDays(supplier.getLeadTimeDays())
                .createdAt(supplier.getCreatedAt())
                .build();
    }

    private static PurchaseOrderDto toDto(PurchaseOrderEntity purchaseOrder) {
        List<PurchaseOrderLineDto> lines = purchaseOrder.getLines().stream().map(line -> PurchaseOrderLineDto.builder()
                .productId(line.getProductId())
                .productName(line.getProductName())
                .orderedQuantity(line.getOrderedQuantity())
                .receivedQuantity(line.getReceivedQuantity())
                .outstandingQuantity(line.outstandingQuantity())
                .unitCost(line.getUnitCost())
                .lineTotal(line.getUnitCost().multiply(BigDecimal.valueOf(line.getOrderedQuantity())))
                .build()).toList();
        return PurchaseOrderDto.builder()
                .id(purchaseOrder.getId())
                .supplierId(purchaseOrder.getSupplier().getId())
                .supplierName(purchaseOrder.getSupplier().getName())
                .status(purchaseOrder.getStatus())
                .note(purchaseOrder.getNote())
                .createdBy(purchaseOrder.getCreatedBy())
                .totalCost(lines.stream().map(PurchaseOrderLineDto::getLineTotal).reduce(BigDecimal.ZERO, BigDecimal::add))
                .lines(lines)
                .createdAt(purchaseOrder.getCreatedAt())
                .updatedAt(purchaseOrder.getUpdatedAt())
                .submittedAt(purchaseOrder.getSubmittedAt())
                .expectedAt(purchaseOrder.getExpectedAt())
                .receivedAt(purchaseOrder.getReceivedAt())
                .cancelledAt(purchaseOrder.getCancelledAt())
                .build();
    }
}
