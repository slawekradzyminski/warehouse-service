package com.awesome.warehouse.support;

import com.awesome.warehouse.client.BackendClient;
import com.awesome.warehouse.client.BackendErrorMapper;
import com.awesome.warehouse.client.FulfilmentState;
import com.awesome.warehouse.client.InternalAddressDto;
import com.awesome.warehouse.client.InternalOrderDto;
import com.awesome.warehouse.client.InternalOrderItemDto;
import com.awesome.warehouse.client.InternalProductDto;
import com.awesome.warehouse.client.InventoryReceiptRequest;
import com.awesome.warehouse.client.InventoryReceiptResponse;
import com.awesome.warehouse.client.NotificationRequest;
import com.awesome.warehouse.client.ReceiptLine;
import com.awesome.warehouse.controller.exception.CustomException;
import com.awesome.warehouse.dto.order.OrderStatus;
import com.awesome.warehouse.dto.order.PageDto;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory backend implementing the internal-API rules from the contract
 * (fulfilment transitions, warehouse-cancel, idempotent receipts), so flow
 * tests run without Docker or HTTP.
 */
public class FakeBackend implements BackendClient {

    private static final Map<FulfilmentState, FulfilmentState> NEXT = Map.of(
            FulfilmentState.NONE, FulfilmentState.AWAITING_PICK,
            FulfilmentState.AWAITING_PICK, FulfilmentState.PICKED,
            FulfilmentState.PICKED, FulfilmentState.PACKED,
            FulfilmentState.PACKED, FulfilmentState.SHIPPED,
            FulfilmentState.SHIPPED, FulfilmentState.DELIVERED);

    private final AtomicLong ids = new AtomicLong(1000);
    private final Map<Long, InternalOrderDto> orders = new ConcurrentHashMap<>();
    private final Map<Long, InternalProductDto> products = new ConcurrentHashMap<>();
    private final Map<UUID, InventoryReceiptRequest> receipts = new ConcurrentHashMap<>();
    private final List<NotificationRequest> notifications = new CopyOnWriteArrayList<>();
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private volatile boolean available = true;
    private volatile boolean refuseFulfilment;

    public void reset() {
        orders.clear();
        products.clear();
        receipts.clear();
        notifications.clear();
        calls.clear();
        available = true;
        refuseFulfilment = false;
    }

    /** Makes PUT fulfilment answer 409 while the order itself still looks releasable. */
    public void setRefuseFulfilment(boolean refuseFulfilment) {
        this.refuseFulfilment = refuseFulfilment;
    }

    public void setAvailable(boolean available) {
        this.available = available;
    }

    public InternalProductDto product(String name, String category, String price, int stock) {
        InternalProductDto product = new InternalProductDto(ids.incrementAndGet(), name, category, new BigDecimal(price), stock);
        products.put(product.id(), product);
        return product;
    }

    public int stockOf(Long productId) {
        return products.get(productId).stockQuantity();
    }

    /** Creates an order; {@code lines} alternate product and quantity. */
    public InternalOrderDto order(String username, OrderStatus status, BigDecimal total, Object... lines) {
        List<InternalOrderItemDto> items = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        for (int i = 0; i < lines.length; i += 2) {
            InternalProductDto product = (InternalProductDto) lines[i];
            int quantity = (Integer) lines[i + 1];
            items.add(new InternalOrderItemDto(ids.incrementAndGet(), product.id(), product.name(), product.category(),
                    quantity, product.price()));
            subtotal = subtotal.add(product.price().multiply(BigDecimal.valueOf(quantity)));
        }
        BigDecimal totalAmount = total == null ? subtotal : total;
        LocalDateTime now = LocalDateTime.now();
        InternalOrderDto order = new InternalOrderDto(ids.incrementAndGet(), username, status, FulfilmentState.NONE,
                subtotal, subtotal.subtract(totalAmount), totalAmount, total == null ? null : "TENOFF",
                new InternalAddressDto("1 Main St", "Krakow", "MA", "30-001", "Poland"), items, now, now);
        orders.put(order.id(), order);
        return order;
    }

    public InternalOrderDto order(Long id) {
        return orders.get(id);
    }

    public void put(InternalOrderDto order) {
        orders.put(order.id(), order);
    }

    /** Customer cancellation on the backend while the order awaits pick. */
    public void cancelByCustomer(Long orderId) {
        InternalOrderDto order = orders.get(orderId);
        orders.put(orderId, with(order, OrderStatus.CANCELLED, FulfilmentState.CANCELLED));
    }

    public List<NotificationRequest> notifications() {
        return List.copyOf(notifications);
    }

    public Collection<InventoryReceiptRequest> receipts() {
        return List.copyOf(receipts.values());
    }

    public List<String> calls() {
        return List.copyOf(calls);
    }

    @Override
    public InternalOrderDto getOrder(long orderId) {
        call("GET order " + orderId);
        InternalOrderDto order = orders.get(orderId);
        if (order == null) {
            throw new CustomException("Order not found", HttpStatus.NOT_FOUND);
        }
        return order;
    }

    @Override
    public PageDto<InternalOrderDto> fulfilmentQueue(int page, int size) {
        call("GET queue");
        List<InternalOrderDto> queue = orders.values().stream()
                .filter(o -> o.status() == OrderStatus.PAID && o.fulfilmentState() == FulfilmentState.NONE)
                .sorted(Comparator.comparing(InternalOrderDto::id))
                .toList();
        PageDto<InternalOrderDto> dto = new PageDto<>();
        List<InternalOrderDto> content = queue.stream().skip((long) page * size).limit(size).toList();
        dto.setContent(content);
        dto.setPageNumber(page);
        dto.setPageSize(size);
        dto.setTotalElements(queue.size());
        dto.setTotalPages((queue.size() + size - 1) / size);
        return dto;
    }

    @Override
    public synchronized InternalOrderDto updateFulfilment(long orderId, FulfilmentState state) {
        call("PUT fulfilment " + orderId + " " + state);
        InternalOrderDto order = getOrderQuietly(orderId);
        boolean allowed = !refuseFulfilment && NEXT.get(order.fulfilmentState()) == state
                && (state != FulfilmentState.AWAITING_PICK || order.status() == OrderStatus.PAID);
        if (!allowed) {
            throw new CustomException("Fulfilment transition not allowed", HttpStatus.CONFLICT);
        }
        OrderStatus status = switch (state) {
            case SHIPPED -> OrderStatus.SHIPPED;
            case DELIVERED -> OrderStatus.DELIVERED;
            default -> order.status();
        };
        InternalOrderDto updated = with(order, status, state);
        orders.put(orderId, updated);
        return updated;
    }

    @Override
    public synchronized InternalOrderDto warehouseCancel(long orderId) {
        call("POST warehouse-cancel " + orderId);
        InternalOrderDto order = getOrderQuietly(orderId);
        if (order.status() != OrderStatus.PAID || !Set.of(FulfilmentState.AWAITING_PICK, FulfilmentState.PICKED)
                .contains(order.fulfilmentState())) {
            throw new CustomException("Order cannot be cancelled by the warehouse", HttpStatus.CONFLICT);
        }
        for (InternalOrderItemDto item : order.items()) {
            adjustStock(item.productId(), item.quantity());
        }
        InternalOrderDto updated = with(order, OrderStatus.CANCELLED, FulfilmentState.CANCELLED);
        orders.put(orderId, updated);
        return updated;
    }

    @Override
    public List<InternalProductDto> getProducts(Collection<Long> productIds) {
        call("GET products " + productIds);
        return productIds.stream().map(products::get).filter(java.util.Objects::nonNull).toList();
    }

    @Override
    public List<InternalProductDto> getAllProducts() {
        call("GET products");
        return products.values().stream().sorted(Comparator.comparing(InternalProductDto::id)).toList();
    }

    @Override
    public synchronized InventoryReceiptResponse receiveInventory(InventoryReceiptRequest request) {
        call("POST receipts " + request.type());
        if (receipts.putIfAbsent(request.requestId(), request) == null) {
            for (ReceiptLine line : request.lines()) {
                if (!products.containsKey(line.productId())) {
                    receipts.remove(request.requestId());
                    throw new CustomException("Product not found", HttpStatus.NOT_FOUND);
                }
                adjustStock(line.productId(), line.quantity());
            }
        }
        return new InventoryReceiptResponse(List.of(ids.incrementAndGet()));
    }

    @Override
    public void sendNotification(NotificationRequest request) {
        call("POST notification " + request.template());
        notifications.add(request);
    }

    private void call(String description) {
        if (!available) {
            throw BackendErrorMapper.unavailable(new IllegalStateException("fake backend down"));
        }
        calls.add(description);
    }

    private InternalOrderDto getOrderQuietly(long orderId) {
        InternalOrderDto order = orders.get(orderId);
        if (order == null) {
            throw new CustomException("Order not found", HttpStatus.NOT_FOUND);
        }
        return order;
    }

    private void adjustStock(Long productId, int delta) {
        products.computeIfPresent(productId, (id, p) ->
                new InternalProductDto(p.id(), p.name(), p.category(), p.price(), p.stockQuantity() + delta));
    }

    public static InternalOrderDto with(InternalOrderDto o, OrderStatus status, FulfilmentState state) {
        return new InternalOrderDto(o.id(), o.username(), status, state, o.subtotalAmount(), o.discountAmount(),
                o.totalAmount(), o.couponCode(), o.shippingAddress(), o.items(), o.createdAt(), LocalDateTime.now());
    }

    public static InternalOrderDto updatedAt(InternalOrderDto o, LocalDateTime updatedAt) {
        return new InternalOrderDto(o.id(), o.username(), o.status(), o.fulfilmentState(), o.subtotalAmount(),
                o.discountAmount(), o.totalAmount(), o.couponCode(), o.shippingAddress(), o.items(), o.createdAt(),
                updatedAt);
    }
}
