package com.awesome.warehouse.client;

import com.awesome.warehouse.controller.exception.CustomException;
import com.awesome.warehouse.dto.order.OrderStatus;
import com.awesome.warehouse.dto.order.PageDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Pins the exact internal-API requests (method, path, query, headers, JSON) the backend must accept. */
class RestBackendClientContractTest {

    private static final String BASE = "http://backend:4001";
    private static final String TOKEN = "test-service-token";
    private static final String ORDER_JSON = """
            {"id":42,"username":"client","status":"PAID","fulfilmentState":"NONE",
             "subtotalAmount":140.00,"discountAmount":14.00,"totalAmount":126.00,"couponCode":"TENOFF",
             "shippingAddress":{"street":"1 Main St","city":"Krakow","state":"MA","zipCode":"30-001","country":"Poland"},
             "items":[{"productId":3,"productName":"Phone","category":"Electronics","quantity":2,"unitPrice":70.00}],
             "createdAt":"2026-11-08T10:15:30","updatedAt":"2026-11-08T10:16:00"}""";

    private MockRestServiceServer server;
    private RestBackendClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestBackendClient(builder, BASE, TOKEN);
    }

    @Test
    void getOrderSendsTheServiceTokenAndParsesTheSnapshot() {
        server.expect(requestTo(BASE + "/internal/v1/orders/42"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Service-Token", TOKEN))
                .andExpect(header("Accept", MediaType.APPLICATION_JSON_VALUE))
                .andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess(ORDER_JSON, MediaType.APPLICATION_JSON));

        InternalOrderDto order = client.getOrder(42);

        server.verify();
        assertThat(order.status()).isEqualTo(OrderStatus.PAID);
        assertThat(order.fulfilmentState()).isEqualTo(FulfilmentState.NONE);
        assertThat(order.totalAmount()).isEqualByComparingTo("126.00");
        assertThat(order.shippingAddress().zipCode()).isEqualTo("30-001");
        assertThat(order.items()).singleElement().satisfies(item -> {
            assertThat(item.category()).isEqualTo("Electronics");
            assertThat(item.id()).isNull();
        });
        assertThat(order.totalUnits()).isEqualTo(2);
        assertThat(order.updatedAt()).hasToString("2026-11-08T10:16");
    }

    @Test
    void queueAsksForPaidOrdersWithoutFulfilment() {
        server.expect(requestTo(BASE + "/internal/v1/orders?status=PAID&fulfilment=NONE&page=2&size=20"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Service-Token", TOKEN))
                .andRespond(withSuccess("{\"content\":[" + ORDER_JSON + "],\"pageNumber\":2,\"pageSize\":20,"
                        + "\"totalElements\":41,\"totalPages\":3}", MediaType.APPLICATION_JSON));

        PageDto<InternalOrderDto> page = client.fulfilmentQueue(2, 20);

        server.verify();
        assertThat(page.getContent()).extracting(InternalOrderDto::id).containsExactly(42L);
        assertThat(page.getTotalElements()).isEqualTo(41);
        assertThat(page.getTotalPages()).isEqualTo(3);
    }

    @Test
    void ordersAreReadInBatchesByCommaSeparatedIds() {
        server.expect(requestTo(BASE + "/internal/v1/orders?ids=42,43"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Service-Token", TOKEN))
                .andRespond(withSuccess("[" + ORDER_JSON + "]", MediaType.APPLICATION_JSON));

        assertThat(client.getOrders(List.of(42L, 43L))).extracting(InternalOrderDto::id).containsExactly(42L);
        assertThat(client.getOrders(List.of())).isEmpty();
        server.verify();
    }

    @Test
    void releasedOrdersAreTheAwaitingPickQueue() {
        server.expect(requestTo(BASE + "/internal/v1/orders?status=PAID&fulfilment=AWAITING_PICK&page=0&size=100"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"content\":[" + ORDER_JSON + "],\"pageNumber\":0,\"pageSize\":100,"
                        + "\"totalElements\":1,\"totalPages\":1}", MediaType.APPLICATION_JSON));

        assertThat(client.ordersAwaitingPick(0, 100).getContent()).extracting(InternalOrderDto::id).containsExactly(42L);
        server.verify();
    }

    @Test
    void refundIsPostedWithItsRequestId() {
        server.expect(requestTo(BASE + "/internal/v1/orders/42/refunds"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Service-Token", TOKEN))
                .andExpect(content().json("""
                        {"requestId":"00000000-0000-0000-0000-000000000007","amount":19.99,"reference":"Return #7"}""",
                        org.springframework.test.json.JsonCompareMode.STRICT))
                .andRespond(withSuccess(ORDER_JSON, MediaType.APPLICATION_JSON));

        assertThat(client.refundOrder(42, new OrderRefundRequest(UUID.fromString("00000000-0000-0000-0000-000000000007"),
                new java.math.BigDecimal("19.99"), "Return #7")).id()).isEqualTo(42L);
        server.verify();
    }

    @Test
    void jwksIsReadFromTheInternalApi() {
        server.expect(requestTo(BASE + "/internal/v1/jwks.json"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Service-Token", TOKEN))
                .andRespond(withSuccess("""
                        {"keys":[{"kty":"RSA","use":"sig","alg":"RS256","kid":"k1","n":"AQAB","e":"AQAB"}]}""",
                        MediaType.APPLICATION_JSON));

        assertThat(client.jwks().keys()).containsExactly(new JwkSet.Jwk("RSA", "k1", "RS256", "sig", "AQAB", "AQAB"));
        server.verify();
    }

    @Test
    void eventFeedIsReadAfterTheCursorWithLimitAndTypes() {
        server.expect(requestTo(BASE + "/internal/v1/events?after=41&limit=100&types=order.cancelled,order.paid"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Service-Token", TOKEN))
                .andExpect(header("Accept", MediaType.APPLICATION_JSON_VALUE))
                .andRespond(withSuccess("""
                        {"events":[{"sequence":42,"id":"6f1c2a9e-0d4b-4c39-9a43-0a6d6f7f0a42","type":"order.cancelled",
                          "occurredAt":"2026-11-11T09:30:15.123Z","orderId":7,"username":"client",
                          "data":{"orderId":7,"status":"CANCELLED","totalAmount":126.00,"failureCode":null}}],
                         "nextCursor":42,"hasMore":true}""", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/internal/v1/events?after=0&limit=1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"events\":[],\"nextCursor\":0,\"hasMore\":false}",
                        MediaType.APPLICATION_JSON));

        EventFeedPage page = client.events(41, 100, List.of("order.cancelled", "order.paid"));
        EventFeedPage empty = client.events(0, 1, List.of());

        server.verify();
        assertThat(page.nextCursor()).isEqualTo(42);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.events()).singleElement().satisfies(event -> {
            assertThat(event.sequence()).isEqualTo(42);
            assertThat(event.id()).isEqualTo(UUID.fromString("6f1c2a9e-0d4b-4c39-9a43-0a6d6f7f0a42"));
            assertThat(event.type()).isEqualTo("order.cancelled");
            assertThat(event.occurredAt()).isEqualTo(java.time.Instant.parse("2026-11-11T09:30:15.123Z"));
            assertThat(event.orderId()).isEqualTo(7L);
            assertThat(event.username()).isEqualTo("client");
            assertThat(event.data()).containsEntry("status", "CANCELLED").containsEntry("failureCode", null);
            assertThat(((Number) event.data().get("orderId")).longValue()).isEqualTo(7L);
        });
        assertThat(empty.events()).isEmpty();
        assertThat(empty.hasMore()).isFalse();
    }

    @Test
    void eventFeedErrorsFollowTheUsualMapping() {
        server.expect(requestTo(BASE + "/internal/v1/events?after=0&limit=100&types=order.cancelled"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"limit must be between 1 and 200\"}"));
        server.expect(requestTo(BASE + "/internal/v1/events?after=0&limit=100&types=order.cancelled"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.events(0, 100, List.of("order.cancelled")))
                .isInstanceOfSatisfying(CustomException.class, e -> {
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).isEqualTo("limit must be between 1 and 200");
                });
        assertUnavailable(() -> client.events(0, 100, List.of("order.cancelled")));
        server.verify();
    }

    @Test
    void fulfilmentUpdateIsAPutWithTheState() {
        server.expect(requestTo(BASE + "/internal/v1/orders/42/fulfilment"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(header("X-Service-Token", TOKEN))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"state\":\"PICKED\"}", org.springframework.test.json.JsonCompareMode.STRICT))
                .andRespond(withSuccess(ORDER_JSON, MediaType.APPLICATION_JSON));

        assertThat(client.updateFulfilment(42, FulfilmentState.PICKED).id()).isEqualTo(42L);

        server.verify();
    }

    @Test
    void warehouseCancelIsABodilessPost() {
        server.expect(requestTo(BASE + "/internal/v1/orders/42/warehouse-cancel"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Service-Token", TOKEN))
                .andExpect(content().string(""))
                .andRespond(withSuccess(ORDER_JSON, MediaType.APPLICATION_JSON));

        assertThat(client.warehouseCancel(42).id()).isEqualTo(42L);
        server.verify();
    }

    @Test
    void productsAreRequestedByCommaSeparatedIdsOrAll() {
        String products = "[{\"id\":3,\"name\":\"Phone\",\"category\":\"Electronics\",\"price\":999.99,\"stockQuantity\":4}]";
        server.expect(requestTo(BASE + "/internal/v1/products?ids=3,7"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Service-Token", TOKEN))
                .andRespond(withSuccess(products, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/internal/v1/products"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Service-Token", TOKEN))
                .andRespond(withSuccess(products, MediaType.APPLICATION_JSON));

        assertThat(client.getProducts(List.of(3L, 7L))).singleElement()
                .isEqualTo(new InternalProductDto(3L, "Phone", "Electronics", new BigDecimal("999.99"), 4));
        assertThat(client.getAllProducts()).hasSize(1);
        assertThat(client.getProducts(List.of())).isEmpty();
        server.verify();
    }

    @Test
    void purchaseReceiptOmitsOrderIdAndReturnRestockCarriesIt() {
        UUID requestId = UUID.fromString("2f1c8a8e-3b0e-4d39-9d43-0a6d6f7f0a11");
        server.expect(requestTo(BASE + "/internal/v1/inventory/receipts"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Service-Token", TOKEN))
                .andExpect(content().json("""
                        {"requestId":"2f1c8a8e-3b0e-4d39-9d43-0a6d6f7f0a11","type":"PURCHASE_RECEIVED",
                         "reference":"Purchase order #9","lines":[{"productId":3,"quantity":4}]}""",
                        org.springframework.test.json.JsonCompareMode.STRICT))
                .andRespond(withSuccess("{\"movementIds\":[11,12]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/internal/v1/inventory/receipts"))
                .andExpect(content().json("""
                        {"requestId":"2f1c8a8e-3b0e-4d39-9d43-0a6d6f7f0a11","type":"RETURN_RESTOCKED","orderId":42,
                         "reference":"Return #5","lines":[{"productId":3,"quantity":1}]}""",
                        org.springframework.test.json.JsonCompareMode.STRICT))
                .andRespond(withSuccess("{\"movementIds\":[]}", MediaType.APPLICATION_JSON));

        InventoryReceiptResponse purchase = client.receiveInventory(new InventoryReceiptRequest(requestId,
                ReceiptType.PURCHASE_RECEIVED, null, "Purchase order #9", List.of(new ReceiptLine(3L, 4))));
        client.receiveInventory(new InventoryReceiptRequest(requestId, ReceiptType.RETURN_RESTOCKED, 42L, "Return #5",
                List.of(new ReceiptLine(3L, 1))));

        server.verify();
        assertThat(purchase.movementIds()).containsExactly(11L, 12L);
    }

    @Test
    void notificationIsPostedAndAcceptedWith202() {
        server.expect(requestTo(BASE + "/internal/v1/notifications"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Service-Token", TOKEN))
                .andExpect(content().json("""
                        {"requestId":"00000000-0000-0000-0000-000000000042","username":"client",
                         "template":"ORDER_SHIPPED","properties":{"orderId":"42"}}""",
                        org.springframework.test.json.JsonCompareMode.STRICT))
                .andRespond(withStatus(HttpStatus.ACCEPTED));

        client.sendNotification(new NotificationRequest(UUID.fromString("00000000-0000-0000-0000-000000000042"),
                "client", NotificationTemplate.ORDER_SHIPPED, Map.of("orderId", "42")));

        server.verify();
    }

    @Test
    void backendClientErrorsKeepTheirStatusAndMessage() {
        server.expect(requestTo(BASE + "/internal/v1/orders/42/fulfilment"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"Fulfilment transition not allowed\"}"));

        assertThatThrownBy(() -> client.updateFulfilment(42, FulfilmentState.PACKED))
                .isInstanceOfSatisfying(CustomException.class, e -> {
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).isEqualTo("Fulfilment transition not allowed");
                });
    }

    @Test
    void notFoundWithoutJsonBodyUsesTheReasonPhrase() {
        server.expect(requestTo(BASE + "/internal/v1/orders/404")).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.getOrder(404))
                .isInstanceOfSatisfying(CustomException.class, e -> {
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getMessage()).isEqualTo("Not Found");
                });
    }

    @Test
    void serverErrorsAndRejectedServiceTokenMeanBackendUnavailable() {
        server.expect(requestTo(BASE + "/internal/v1/orders/1"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("{\"message\":\"boom\"}"));
        server.expect(requestTo(BASE + "/internal/v1/orders/2"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("{\"message\":\"Unauthorized\"}"));
        server.expect(requestTo(BASE + "/internal/v1/orders/3"))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        assertUnavailable(() -> client.getOrder(1));
        assertUnavailable(() -> client.getOrder(2));
        assertUnavailable(() -> client.getOrder(3));
    }

    @Test
    void connectionFailureMeansBackendUnavailable() {
        RestBackendClient unreachable = new RestBackendClient(RestClient.builder(), "http://127.0.0.1:1", TOKEN);

        assertUnavailable(() -> unreachable.getOrder(1));
    }

    @Test
    void missingServiceTokenFailsFast() {
        assertThatThrownBy(() -> new RestBackendClient(RestClient.builder(), BASE, " "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("warehouse.service-token must be configured");
        assertThatThrownBy(() -> new RestBackendClient(RestClient.builder(), BASE, null))
                .isInstanceOf(IllegalStateException.class);
    }

    private static void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(CustomException.class, e -> {
            assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(e.getMessage()).isEqualTo("Backend unavailable");
        });
    }
}
