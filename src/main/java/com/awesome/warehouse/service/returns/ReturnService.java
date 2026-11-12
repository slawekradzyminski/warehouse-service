package com.awesome.warehouse.service.returns;

import com.awesome.warehouse.client.BackendClient;
import com.awesome.warehouse.client.InternalOrderDto;
import com.awesome.warehouse.client.InternalOrderItemDto;
import com.awesome.warehouse.client.InventoryReceiptRequest;
import com.awesome.warehouse.client.OrderRefundRequest;
import com.awesome.warehouse.client.ReceiptLine;
import com.awesome.warehouse.client.ReceiptType;
import com.awesome.warehouse.controller.exception.CustomException;
import com.awesome.warehouse.dto.order.OrderStatus;
import com.awesome.warehouse.dto.returns.CreateReturnDto;
import com.awesome.warehouse.dto.returns.ReceiveItemDto;
import com.awesome.warehouse.dto.returns.ReturnDto;
import com.awesome.warehouse.dto.returns.ReturnItemDto;
import com.awesome.warehouse.dto.returns.ReturnItemRequestDto;
import com.awesome.warehouse.entity.fulfilment.ShipmentEntity;
import com.awesome.warehouse.entity.returns.ItemCondition;
import com.awesome.warehouse.entity.returns.ReturnItemEntity;
import com.awesome.warehouse.entity.returns.ReturnRequestEntity;
import com.awesome.warehouse.entity.returns.ReturnStatus;
import com.awesome.warehouse.repository.fulfilment.ShipmentRepository;
import com.awesome.warehouse.repository.returns.ReturnRequestRepository;
import com.awesome.warehouse.service.notification.CustomerMessages;
import lombok.RequiredArgsConstructor;
import com.awesome.warehouse.service.notification.NotificationOutbox;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReturnService {

    static final Duration RETURN_WINDOW = Duration.ofDays(30);

    private final ReturnRequestRepository returnRepository;
    private final ShipmentRepository shipmentRepository;
    private final BackendClient backendClient;
    private final OrderLocks orderLocks;
    private final NotificationOutbox notificationOutbox;
    private final Clock warehouseClock;

    /**
     * Opens a return. Requests for the same order are serialised with a database
     * row lock, so concurrent requests cannot together return more units than were
     * bought, whichever instance serves them. The order is read from the backend
     * before the locking transaction starts, so no connection waits for that call.
     */
    public ReturnDto create(String username, Long orderId, CreateReturnDto request) {
        InternalOrderDto order = backendClient.getOrder(orderId);
        if (!order.username().equals(username)) {
            throw new CustomException("Order not found", HttpStatus.NOT_FOUND);
        }
        if (order.status() != OrderStatus.DELIVERED) {
            throw new CustomException("Only delivered orders can be returned", HttpStatus.CONFLICT);
        }
        return orderLocks.callLocked(orderId, () -> createLocked(username, order, request));
    }

    private ReturnDto createLocked(String username, InternalOrderDto order, CreateReturnDto request) {
        Long orderId = order.id();
        LocalDateTime deliveredAt = shipmentRepository.findByOrderId(orderId)
                .map(ShipmentEntity::getDeliveredAt)
                .orElse(order.updatedAt());
        if (LocalDateTime.now(warehouseClock).isAfter(deliveredAt.plus(RETURN_WINDOW))) {
            throw new CustomException("The 30-day return window has passed", HttpStatus.CONFLICT);
        }

        Map<Long, Integer> ordered = new HashMap<>();
        Map<Long, InternalOrderItemDto> firstItem = new HashMap<>();
        for (InternalOrderItemDto item : order.items()) {
            ordered.merge(item.productId(), item.quantity(), Integer::sum);
            firstItem.putIfAbsent(item.productId(), item);
        }
        Map<Long, Integer> alreadyReturned = new HashMap<>();
        for (ReturnRequestEntity previous : returnRepository.findByOrderIdAndStatusNot(orderId, ReturnStatus.REJECTED)) {
            for (ReturnItemEntity item : previous.getItems()) {
                alreadyReturned.merge(item.getProductId(), item.getQuantity(), Integer::sum);
            }
        }

        ReturnRequestEntity returnRequest = ReturnRequestEntity.builder()
                .orderId(orderId)
                .orderTotalAmount(order.totalAmount())
                .orderSubtotalAmount(order.subtotalAmount())
                .username(username)
                .status(ReturnStatus.REQUESTED)
                .reason(request.getReason())
                .comment(blankToNull(request.getComment()))
                .build();
        Set<Long> seen = new HashSet<>();
        for (ReturnItemRequestDto item : request.getItems()) {
            Long productId = item.getProductId();
            if (!seen.add(productId)) {
                throw new CustomException("Product " + productId + " is listed more than once", HttpStatus.BAD_REQUEST);
            }
            if (!ordered.containsKey(productId)) {
                throw new CustomException("Product " + productId + " is not part of this order", HttpStatus.BAD_REQUEST);
            }
            int returnable = ordered.get(productId) - alreadyReturned.getOrDefault(productId, 0);
            if (item.getQuantity() > returnable) {
                throw new CustomException("Requested quantity for product " + productId
                        + " exceeds the returnable quantity of " + returnable, HttpStatus.CONFLICT);
            }
            InternalOrderItemDto orderItem = firstItem.get(productId);
            returnRequest.addItem(ReturnItemEntity.builder()
                    .productId(productId)
                    .productName(orderItem.productName())
                    .quantity(item.getQuantity())
                    .unitPrice(orderItem.unitPrice())
                    .build());
        }
        return toDto(returnRepository.saveAndFlush(returnRequest));
    }

    @Transactional(readOnly = true)
    public Page<ReturnDto> mine(String username, Pageable pageable) {
        return returnRepository.findByUsername(username, pageable).map(this::toDto);
    }

    @Transactional(readOnly = true)
    public ReturnDto getMine(String username, Long id) {
        return returnRepository.findByIdAndUsername(id, username)
                .map(this::toDto)
                .orElseThrow(ReturnService::notFound);
    }

    @Transactional(readOnly = true)
    public Page<ReturnDto> list(ReturnStatus status, Pageable pageable) {
        Page<ReturnRequestEntity> page = status == null
                ? returnRepository.findAll(pageable)
                : returnRepository.findByStatus(status, pageable);
        return page.map(this::toDto);
    }

    @Transactional(readOnly = true)
    public ReturnDto get(Long id) {
        return returnRepository.findById(id).map(this::toDto).orElseThrow(ReturnService::notFound);
    }

    @Transactional
    public ReturnDto approve(Long id) {
        ReturnRequestEntity request = lock(id);
        require(request, "approve", ReturnStatus.REQUESTED);
        request.setStatus(ReturnStatus.APPROVED);
        request.setApprovedAt(now());
        return saveAndNotify(request);
    }

    @Transactional
    public ReturnDto reject(Long id, String note) {
        ReturnRequestEntity request = lock(id);
        require(request, "reject", ReturnStatus.REQUESTED);
        request.setStatus(ReturnStatus.REJECTED);
        request.setResolutionNote(note.trim());
        request.setRejectedAt(now());
        return saveAndNotify(request);
    }

    /**
     * Records the inspection result. The request must name every returned
     * product exactly once; resellable units are restocked on the backend
     * (idempotently, keyed by the return) before the return is marked RECEIVED.
     */
    @Transactional
    public ReturnDto receive(Long id, List<ReceiveItemDto> conditions, String actor) {
        ReturnRequestEntity request = lock(id);
        require(request, "receive", ReturnStatus.APPROVED);
        Map<Long, ItemCondition> byProduct = new HashMap<>();
        for (ReceiveItemDto condition : conditions) {
            if (byProduct.put(condition.getProductId(), condition.getCondition()) != null) {
                throw new CustomException(
                        "Product " + condition.getProductId() + " is listed more than once", HttpStatus.BAD_REQUEST);
            }
        }
        Map<Long, ReturnItemEntity> items = request.getItems().stream()
                .collect(Collectors.toMap(ReturnItemEntity::getProductId, Function.identity(), (a, b) -> a, TreeMap::new));
        if (!byProduct.keySet().equals(items.keySet())) {
            throw new CustomException(
                    "Conditions must cover exactly the returned products " + items.keySet(), HttpStatus.BAD_REQUEST);
        }
        List<ReceiptLine> restock = items.values().stream()
                .filter(item -> byProduct.get(item.getProductId()) == ItemCondition.RESELLABLE)
                .map(item -> new ReceiptLine(item.getProductId(), item.getQuantity()))
                .toList();
        if (!restock.isEmpty()) {
            backendClient.receiveInventory(new InventoryReceiptRequest(
                    restockRequestId(request.getId()), ReceiptType.RETURN_RESTOCKED, request.getOrderId(),
                    "Return #" + request.getId(), restock));
        }
        items.forEach((productId, item) -> item.setCondition(byProduct.get(productId)));
        request.setStatus(ReturnStatus.RECEIVED);
        request.setReceivedAt(now());
        return toDto(returnRepository.saveAndFlush(request));
    }

    @Transactional
    public ReturnDto refund(Long id) {
        ReturnRequestEntity request = lock(id);
        require(request, "refund", ReturnStatus.RECEIVED);
        BigDecimal amount = estimatedRefund(request);
        if (amount.signum() > 0) {
            backendClient.refundOrder(request.getOrderId(),
                    new OrderRefundRequest(refundRequestId(request.getId()), amount, "Return #" + request.getId()));
        }
        request.setRefundAmount(amount);
        request.setStatus(ReturnStatus.REFUNDED);
        request.setRefundedAt(now());
        return saveAndNotify(request);
    }

    /** Same id for every attempt to refund one return, so the backend books the money once. */
    static UUID refundRequestId(Long returnId) {
        return UUID.nameUUIDFromBytes(("refund-" + returnId).getBytes(StandardCharsets.UTF_8));
    }

    /** Same id for every attempt to restock one return, so the backend books it once. */
    static UUID restockRequestId(Long returnId) {
        return UUID.nameUUIDFromBytes(("return-" + returnId).getBytes(StandardCharsets.UTF_8));
    }

    static BigDecimal estimatedRefund(ReturnRequestEntity request) {
        BigDecimal itemsValue = request.getItems().stream()
                .map(item -> item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return RefundCalculator.proportionalRefund(
                itemsValue, request.getOrderTotalAmount(), request.getOrderSubtotalAmount());
    }

    private ReturnDto saveAndNotify(ReturnRequestEntity request) {
        ReturnDto result = toDto(returnRepository.saveAndFlush(request));
        notificationOutbox.enqueue(CustomerMessages.returnUpdated(request));
        return result;
    }

    private ReturnRequestEntity lock(Long id) {
        return returnRepository.findByIdForUpdate(id).orElseThrow(ReturnService::notFound);
    }

    private static void require(ReturnRequestEntity request, String action, ReturnStatus expected) {
        if (request.getStatus() != expected) {
            throw new CustomException(
                    "Cannot " + action + " a return in status " + request.getStatus(), HttpStatus.CONFLICT);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(warehouseClock);
    }

    private static CustomException notFound() {
        return new CustomException("Return not found", HttpStatus.NOT_FOUND);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private ReturnDto toDto(ReturnRequestEntity request) {
        return ReturnDto.builder()
                .id(request.getId())
                .orderId(request.getOrderId())
                .username(request.getUsername())
                .status(request.getStatus())
                .reason(request.getReason())
                .comment(request.getComment())
                .resolutionNote(request.getResolutionNote())
                .estimatedRefund(estimatedRefund(request))
                .refundAmount(request.getRefundAmount())
                .items(request.getItems().stream().map(item -> ReturnItemDto.builder()
                        .productId(item.getProductId())
                        .productName(item.getProductName())
                        .quantity(item.getQuantity())
                        .unitPrice(item.getUnitPrice())
                        .condition(item.getCondition())
                        .build()).toList())
                .createdAt(request.getCreatedAt())
                .updatedAt(request.getUpdatedAt())
                .approvedAt(request.getApprovedAt())
                .rejectedAt(request.getRejectedAt())
                .receivedAt(request.getReceivedAt())
                .refundedAt(request.getRefundedAt())
                .build();
    }
}
