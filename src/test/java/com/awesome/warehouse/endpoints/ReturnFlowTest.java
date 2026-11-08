package com.awesome.warehouse.endpoints;

import com.awesome.warehouse.client.InternalOrderDto;
import com.awesome.warehouse.client.InternalProductDto;
import com.awesome.warehouse.client.InventoryReceiptRequest;
import com.awesome.warehouse.client.NotificationRequest;
import com.awesome.warehouse.client.ReceiptLine;
import com.awesome.warehouse.client.ReceiptType;
import com.awesome.warehouse.dto.order.OrderStatus;
import com.awesome.warehouse.dto.order.PageDto;
import com.awesome.warehouse.dto.returns.CreateReturnDto;
import com.awesome.warehouse.dto.returns.ReceiveItemDto;
import com.awesome.warehouse.dto.returns.ReceiveReturnDto;
import com.awesome.warehouse.dto.returns.RejectReturnDto;
import com.awesome.warehouse.dto.returns.ReturnDto;
import com.awesome.warehouse.dto.returns.ReturnItemRequestDto;
import com.awesome.warehouse.entity.returns.ItemCondition;
import com.awesome.warehouse.entity.returns.ReturnReason;
import com.awesome.warehouse.entity.returns.ReturnStatus;
import com.awesome.warehouse.support.AbstractFlowTest;
import com.awesome.warehouse.support.FakeBackend;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReturnFlowTest extends AbstractFlowTest {

    private static final String ADMIN_RETURNS = "/api/v1/admin/returns";

    @Test
    void returnOfADiscountedOrderRestocksResellableItemsAndRefundsProportionally() {
        InternalProductDto phone = backend.product("Phone", "Electronics", "100.00", 10);
        InternalProductDto cable = backend.product("Cable", "Accessories", "20.00", 10);
        // 10% coupon: subtotal 140.00, total 126.00
        InternalOrderDto order = backend.order("client", OrderStatus.DELIVERED, new BigDecimal("126.00"),
                phone, 1, cable, 2);

        ReturnDto requested = requestReturn(order, List.of(item(phone, 1), item(cable, 1))).as(ReturnDto.class);
        postJson(ADMIN_RETURNS + "/" + requested.getId() + "/approve", null, adminToken);
        ReturnDto received = postJson(ADMIN_RETURNS + "/" + requested.getId() + "/receive",
                new ReceiveReturnDto(List.of(new ReceiveItemDto(phone.id(), ItemCondition.RESELLABLE),
                        new ReceiveItemDto(cable.id(), ItemCondition.DAMAGED))), adminToken).as(ReturnDto.class);
        ReturnDto refunded = postJson(ADMIN_RETURNS + "/" + requested.getId() + "/refund", null, adminToken)
                .as(ReturnDto.class);

        assertThat(requested.getStatus()).isEqualTo(ReturnStatus.REQUESTED);
        assertThat(requested.getItems()).extracting(i -> i.getProductName()).containsExactly("Phone", "Cable");
        // 120.00 of goods × 126/140 = 108.00; ignoring the coupon would refund 120.00
        assertThat(requested.getEstimatedRefund()).isEqualByComparingTo("108.00");
        assertThat(received.getStatus()).isEqualTo(ReturnStatus.RECEIVED);
        assertThat(backend.stockOf(phone.id())).isEqualTo(11);
        assertThat(backend.stockOf(cable.id())).isEqualTo(10);
        assertThat(backend.receipts()).singleElement().satisfies(receipt -> {
            assertThat(receipt.type()).isEqualTo(ReceiptType.RETURN_RESTOCKED);
            assertThat(receipt.requestId()).isEqualTo(UUID.nameUUIDFromBytes(
                    ("return-" + requested.getId()).getBytes(StandardCharsets.UTF_8)));
            assertThat(receipt.orderId()).isEqualTo(order.id());
            assertThat(receipt.reference()).isEqualTo("Return #" + requested.getId());
            assertThat(receipt.lines()).containsExactly(new ReceiptLine(phone.id(), 1));
        });
        assertThat(refunded.getStatus()).isEqualTo(ReturnStatus.REFUNDED);
        assertThat(refunded.getRefundAmount()).isEqualByComparingTo("108.00");
        assertThat(backend.notifications()).extracting(NotificationRequest::subject).containsExactly(
                "Return #" + requested.getId() + " is APPROVED",
                "Return #" + requested.getId() + " is REFUNDED");
        assertThat(backend.notifications().getLast().message()).contains("Refund: 108.00.");
    }

    @Test
    void returnsRequireADeliveredOrderAndRespectReturnableQuantities() {
        InternalProductDto product = backend.product("Phone", "Electronics", "10.00", 10);
        InternalOrderDto order = backend.order("client", OrderStatus.SHIPPED, null, product, 2);

        Response notDelivered = requestReturn(order, List.of(item(product, 1)));
        backend.put(FakeBackend.with(order, OrderStatus.DELIVERED, order.fulfilmentState()));
        Response first = requestReturn(order, List.of(item(product, 2)));
        Response tooMany = requestReturn(order, List.of(item(product, 1)));
        postJson(ADMIN_RETURNS + "/" + first.as(ReturnDto.class).getId() + "/reject",
                new RejectReturnDto("Missing packaging"), adminToken);
        Response afterRejection = requestReturn(order, List.of(item(product, 2)));

        assertThat(notDelivered.status()).isEqualTo(409);
        assertThat(notDelivered.message()).isEqualTo("Only delivered orders can be returned");
        assertThat(first.status()).isEqualTo(201);
        assertThat(tooMany.status()).isEqualTo(409);
        assertThat(tooMany.message())
                .isEqualTo("Requested quantity for product " + product.id() + " exceeds the returnable quantity of 0");
        assertThat(afterRejection.status()).isEqualTo(201);
    }

    @Test
    void returnWindowClosesThirtyDaysAfterDelivery() {
        InternalProductDto product = backend.product("Phone", "Electronics", "10.00", 10);
        InternalOrderDto order = backend.order("client", OrderStatus.DELIVERED, null, product, 1);
        backend.put(FakeBackend.updatedAt(order, LocalDateTime.now().minusDays(31)));

        Response late = requestReturn(order, List.of(item(product, 1)));

        assertThat(late.status()).isEqualTo(409);
        assertThat(late.message()).isEqualTo("The 30-day return window has passed");
    }

    @Test
    void customersCannotReturnOtherPeoplesOrdersOrProductsTheyDidNotBuy() {
        InternalProductDto bought = backend.product("Phone", "Electronics", "10.00", 10);
        InternalProductDto notBought = backend.product("Cable", "Electronics", "10.00", 10);
        InternalOrderDto order = backend.order("client", OrderStatus.DELIVERED, null, bought, 1);

        Response stranger = postJson(ORDERS + "/" + order.id() + "/returns",
                new CreateReturnDto(ReturnReason.DAMAGED, null, List.of(item(bought, 1))), otherClientToken);
        Response wrongProduct = requestReturn(order, List.of(item(notBought, 1)));
        Response duplicate = requestReturn(order, List.of(item(bought, 1), item(bought, 1)));
        Response invalid = postJson(ORDERS + "/" + order.id() + "/returns",
                new CreateReturnDto(null, null, List.of()), clientToken);

        assertThat(stranger.status()).isEqualTo(404);
        assertThat(stranger.message()).isEqualTo("Order not found");
        assertThat(wrongProduct.status()).isEqualTo(400);
        assertThat(wrongProduct.message()).isEqualTo("Product " + notBought.id() + " is not part of this order");
        assertThat(duplicate.message()).isEqualTo("Product " + bought.id() + " is listed more than once");
        assertThat(invalid.status()).isEqualTo(400);
        assertThat(invalid.body()).contains("Reason is required", "At least one item is required");
    }

    @Test
    void adminTransitionsFollowTheStateMachineAndCustomersSeeOnlyTheirReturns() {
        InternalProductDto product = backend.product("Phone", "Electronics", "10.00", 10);
        InternalOrderDto order = backend.order("client", OrderStatus.DELIVERED, null, product, 1);
        ReturnDto requested = requestReturn(order, List.of(item(product, 1))).as(ReturnDto.class);

        Response refundTooEarly = postJson(ADMIN_RETURNS + "/" + requested.getId() + "/refund", null, adminToken);
        postJson(ADMIN_RETURNS + "/" + requested.getId() + "/approve", null, adminToken);
        Response incompleteInspection = postJson(ADMIN_RETURNS + "/" + requested.getId() + "/receive",
                new ReceiveReturnDto(List.of(new ReceiveItemDto(product.id() + 999, ItemCondition.RESELLABLE))),
                adminToken);
        Response rejectApproved = postJson(ADMIN_RETURNS + "/" + requested.getId() + "/reject",
                new RejectReturnDto("Too late"), adminToken);
        PageDto<ReturnDto> mine = getJson("/api/v1/returns", clientToken).as(new TypeReference<PageDto<ReturnDto>>() {});
        PageDto<ReturnDto> approved = getJson(ADMIN_RETURNS + "?status=APPROVED", adminToken)
                .as(new TypeReference<PageDto<ReturnDto>>() {});

        assertThat(refundTooEarly.status()).isEqualTo(409);
        assertThat(refundTooEarly.message()).isEqualTo("Cannot refund a return in status REQUESTED");
        assertThat(incompleteInspection.status()).isEqualTo(400);
        assertThat(rejectApproved.status()).isEqualTo(409);
        assertThat(mine.getContent()).extracting(ReturnDto::getId).containsExactly(requested.getId());
        assertThat(approved.getContent()).extracting(ReturnDto::getId).containsExactly(requested.getId());
        assertThat(getJson("/api/v1/returns/" + requested.getId(), clientToken).status()).isEqualTo(200);
        assertThat(getJson("/api/v1/returns/" + requested.getId(), otherClientToken).status()).isEqualTo(404);
        assertThat(getJson(ADMIN_RETURNS + "/" + requested.getId(), adminToken).status()).isEqualTo(200);
        assertThat(getJson(ADMIN_RETURNS, warehouseToken).status()).isEqualTo(403);
        assertThat(getJson(ADMIN_RETURNS, clientToken).status()).isEqualTo(403);
    }

    @Test
    void failedRestockLeavesTheReturnApprovedAndARetryBooksStockOnce() {
        InternalProductDto product = backend.product("Phone", "Electronics", "10.00", 10);
        InternalOrderDto order = backend.order("client", OrderStatus.DELIVERED, null, product, 1);
        ReturnDto requested = requestReturn(order, List.of(item(product, 1))).as(ReturnDto.class);
        postJson(ADMIN_RETURNS + "/" + requested.getId() + "/approve", null, adminToken);
        ReceiveReturnDto inspection = new ReceiveReturnDto(List.of(new ReceiveItemDto(product.id(), ItemCondition.RESELLABLE)));

        backend.setAvailable(false);
        Response failed = postJson(ADMIN_RETURNS + "/" + requested.getId() + "/receive", inspection, adminToken);
        backend.setAvailable(true);
        ReturnDto stillApproved = getJson(ADMIN_RETURNS + "/" + requested.getId(), adminToken).as(ReturnDto.class);
        Response retried = postJson(ADMIN_RETURNS + "/" + requested.getId() + "/receive", inspection, adminToken);

        assertThat(failed.status()).isEqualTo(503);
        assertThat(stillApproved.getStatus()).isEqualTo(ReturnStatus.APPROVED);
        assertThat(stillApproved.getItems().getFirst().getCondition()).isNull();
        assertThat(retried.status()).isEqualTo(200);
        assertThat(backend.stockOf(product.id())).isEqualTo(11);
        assertThat(backend.receipts()).extracting(InventoryReceiptRequest::type)
                .containsExactly(ReceiptType.RETURN_RESTOCKED);
    }

    private ReturnItemRequestDto item(InternalProductDto product, int quantity) {
        return new ReturnItemRequestDto(product.id(), quantity);
    }

    private Response requestReturn(InternalOrderDto order, List<ReturnItemRequestDto> items) {
        return postJson(ORDERS + "/" + order.id() + "/returns",
                new CreateReturnDto(ReturnReason.NOT_AS_DESCRIBED, "Not what I expected", items), clientToken);
    }
}
