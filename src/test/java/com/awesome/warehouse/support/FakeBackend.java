package com.awesome.warehouse.support;

import com.awesome.warehouse.client.BackendClient;
import com.awesome.warehouse.client.BackendErrorMapper;
import com.awesome.warehouse.client.EventFeedPage;
import com.awesome.warehouse.client.FeedEvent;
import com.awesome.warehouse.client.FulfilmentState;
import com.awesome.warehouse.client.InternalAddressDto;
import com.awesome.warehouse.client.InternalOrderDto;
import com.awesome.warehouse.client.InternalOrderItemDto;
import com.awesome.warehouse.client.InternalProductDto;
import com.awesome.warehouse.client.InventoryReceiptRequest;
import com.awesome.warehouse.client.InventoryReceiptResponse;
import com.awesome.warehouse.client.NotificationRequest;
import com.awesome.warehouse.client.JwkSet;
import com.awesome.warehouse.client.OrderRefundRequest;
import com.awesome.warehouse.client.ReceiptLine;
import com.awesome.warehouse.controller.exception.CustomException;
import com.awesome.warehouse.dto.order.OrderStatus;
import com.awesome.warehouse.dto.order.PageDto;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Instant;
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
 * (fulfilment transitions, warehouse-cancel, idempotent receipts, the domain
 * event feed), so flow tests run without Docker or HTTP.
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
    private final Map<UUID, OrderRefundRequest> refunds = new ConcurrentHashMap<>();
    private final Map<Long, BigDecimal> refunded = new ConcurrentHashMap<>();
    private final List<FeedEvent> feed = new CopyOnWriteArrayList<>();
    private final AtomicLong sequences = new AtomicLong();
    private volatile boolean available = true;
    private volatile boolean refuseFulfilment;
    private volatile boolean loseNextFulfilmentResponse;
    private volatile boolean notificationsDown;

    public void reset() {
        orders.clear();
        products.clear();
        receipts.clear();
        notifications.clear();
        calls.clear();
        refunds.clear();
        refunded.clear();
        feed.clear();
        sequences.set(0);
        available = true;
        refuseFulfilment = false;
        loseNextFulfilmentResponse = false;
        notificationsDown = false;
    }

    /** The next PUT fulfilment is applied, but the caller gets 503 as if the response timed out. */
    public void loseNextFulfilmentResponse() {
        this.loseNextFulfilmentResponse = true;
    }

    /** Makes POST notifications answer 503 while every other call works. */
    public void setNotificationsDown(boolean notificationsDown) {
        this.notificationsDown = notificationsDown;
    }

    public BigDecimal refundedAmount(Long orderId) {
        return refunded.getOrDefault(orderId, BigDecimal.ZERO);
    }

    public Collection<OrderRefundRequest> refunds() {
        return List.copyOf(refunds.values());
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

    /** Customer cancellation on the backend while the order awaits pick; publishes {@code order.cancelled}. */
    public void cancelByCustomer(Long orderId) {
        InternalOrderDto order = orders.get(orderId);
        InternalOrderDto cancelled = with(order, OrderStatus.CANCELLED, FulfilmentState.CANCELLED);
        orders.put(orderId, cancelled);
        publish("order.cancelled", cancelled);
    }

    /** Appends a domain event for the order to the feed, like the backend's synchronous event listener. */
    public FeedEvent publish(String type, InternalOrderDto order) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("orderId", order.id());
        data.put("status", order.status().name());
        data.put("totalAmount", order.totalAmount());
        return publish(new FeedEvent(0, UUID.randomUUID(), type, Instant.now(), order.id(), order.username(), data));
    }

    /** Appends the event with the next sequence (the given sequence is ignored). */
    public FeedEvent publish(FeedEvent event) {
        FeedEvent stored = new FeedEvent(sequences.incrementAndGet(), event.id(), event.type(), event.occurredAt(),
                event.orderId(), event.username(), event.data());
        feed.add(stored);
        return stored;
    }

    /** Lets the sequence jump, as when the backend has events of types this consumer does not read. */
    public void skipSequences(long count) {
        sequences.addAndGet(count);
    }

    public List<FeedEvent> feed() {
        return List.copyOf(feed);
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
        return page(queue, page, size);
    }

    private static PageDto<InternalOrderDto> page(List<InternalOrderDto> all, int page, int size) {
        PageDto<InternalOrderDto> dto = new PageDto<>();
        dto.setContent(all.stream().skip((long) page * size).limit(size).toList());
        dto.setPageNumber(page);
        dto.setPageSize(size);
        dto.setTotalElements(all.size());
        dto.setTotalPages((all.size() + size - 1) / size);
        return dto;
    }

    @Override
    public synchronized InternalOrderDto updateFulfilment(long orderId, FulfilmentState state) {
        call("PUT fulfilment " + orderId + " " + state);
        InternalOrderDto order = getOrderQuietly(orderId);
        if (!refuseFulfilment && order.fulfilmentState() == state) {
            return order;
        }
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
        if (loseNextFulfilmentResponse) {
            loseNextFulfilmentResponse = false;
            throw BackendErrorMapper.unavailable(new IllegalStateException("fake backend response lost"));
        }
        return updated;
    }

    @Override
    public List<InternalOrderDto> getOrders(Collection<Long> orderIds) {
        call("GET orders " + orderIds);
        return orderIds.stream().map(orders::get).filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(InternalOrderDto::id)).toList();
    }

    @Override
    public PageDto<InternalOrderDto> ordersAwaitingPick(int page, int size) {
        call("GET awaiting pick");
        return page(orders.values().stream()
                .filter(o -> o.status() == OrderStatus.PAID && o.fulfilmentState() == FulfilmentState.AWAITING_PICK)
                .sorted(Comparator.comparing(InternalOrderDto::id))
                .toList(), page, size);
    }

    @Override
    public synchronized InternalOrderDto refundOrder(long orderId, OrderRefundRequest request) {
        call("POST refund " + orderId);
        InternalOrderDto order = getOrderQuietly(orderId);
        OrderRefundRequest previous = refunds.get(request.requestId());
        if (previous != null) {
            if (previous.amount().compareTo(request.amount()) != 0) {
                throw new CustomException("requestId already used with a different refund", HttpStatus.CONFLICT);
            }
            return order;
        }
        if (order.status() != OrderStatus.DELIVERED) {
            throw new CustomException("Only delivered orders can be refunded", HttpStatus.CONFLICT);
        }
        BigDecimal total = refundedAmount(orderId).add(request.amount());
        if (total.compareTo(order.totalAmount()) > 0) {
            throw new CustomException("Refund exceeds the refundable amount", HttpStatus.CONFLICT);
        }
        refunds.put(request.requestId(), request);
        refunded.put(orderId, total);
        return order;
    }

    @Override
    public EventFeedPage events(long after, int limit, Collection<String> types) {
        call("GET events after " + after);
        if (limit < 1 || limit > 200 || after < 0) {
            throw new CustomException("Invalid event feed parameters", HttpStatus.BAD_REQUEST);
        }
        List<FeedEvent> matching = feed.stream()
                .filter(e -> e.sequence() > after)
                .filter(e -> types.isEmpty() || types.contains(e.type()))
                .toList();
        List<FeedEvent> page = matching.stream().limit(limit).toList();
        boolean hasMore = matching.size() > limit;
        // Without more matching events the cursor moves to the newest sequence scanned.
        long nextCursor = hasMore ? page.getLast().sequence() : Math.max(after, sequences.get());
        return new EventFeedPage(page, nextCursor, hasMore);
    }

    @Override
    public JwkSet jwks() {
        call("GET jwks");
        return TestTokens.jwks();
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
        publish("order.cancelled", updated);
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
        if (notificationsDown) {
            throw BackendErrorMapper.unavailable(new IllegalStateException("fake notifications down"));
        }
        if (notifications.stream().noneMatch(sent -> sent.requestId().equals(request.requestId()))) {
            notifications.add(request);
        }
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
