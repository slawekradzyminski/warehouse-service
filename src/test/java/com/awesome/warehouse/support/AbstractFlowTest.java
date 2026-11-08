package com.awesome.warehouse.support;

import com.awesome.warehouse.repository.fulfilment.ShipmentRepository;
import com.awesome.warehouse.repository.purchasing.PurchaseOrderRepository;
import com.awesome.warehouse.repository.purchasing.PurchaseReceiptRepository;
import com.awesome.warehouse.repository.purchasing.SupplierRepository;
import com.awesome.warehouse.repository.returns.ReturnRequestRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** Boots the whole service on H2 with the in-memory backend and drives it over MockMvc. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(TestBackendConfig.class)
public abstract class AbstractFlowTest {

    protected static final String WAREHOUSE = "/api/v1/warehouse";
    protected static final String ORDERS = "/api/v1/orders";

    protected final String adminToken = TestTokens.token("admin", "ROLE_ADMIN");
    protected final String warehouseToken = TestTokens.token("warehouse", "ROLE_WAREHOUSE");
    protected final String clientToken = TestTokens.token("client", "ROLE_CLIENT");
    protected final String otherClientToken = TestTokens.token("other", "ROLE_CLIENT");

    @Autowired protected MockMvc mockMvc;
    @Autowired protected JsonMapper jsonMapper;
    @Autowired protected FakeBackend backend;
    @Autowired private ShipmentRepository shipmentRepository;
    @Autowired private ReturnRequestRepository returnRepository;
    @Autowired private PurchaseReceiptRepository receiptRepository;
    @Autowired private PurchaseOrderRepository purchaseOrderRepository;
    @Autowired private SupplierRepository supplierRepository;

    @BeforeEach
    void resetBackend() {
        backend.reset();
    }

    @AfterEach
    void cleanDatabase() {
        receiptRepository.deleteAll();
        purchaseOrderRepository.deleteAll();
        supplierRepository.deleteAll();
        returnRepository.deleteAll();
        shipmentRepository.deleteAll();
    }

    protected Response getJson(String path, String token) {
        return perform(get(path), token);
    }

    protected Response postJson(String path, Object body, String token) {
        return perform(withBody(post(path), body), token);
    }

    protected Response putJson(String path, Object body, String token) {
        return perform(withBody(put(path), body), token);
    }

    private MockHttpServletRequestBuilder withBody(MockHttpServletRequestBuilder builder, Object body) {
        if (body == null) {
            return builder;
        }
        return builder.contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(body));
    }

    private Response perform(MockHttpServletRequestBuilder builder, String token) {
        if (token != null) {
            builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        try {
            return new Response(mockMvc.perform(builder).andReturn(), jsonMapper);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Status plus a typed view of the JSON body. */
    public record Response(MvcResult result, JsonMapper mapper) {

        public int status() {
            return result.getResponse().getStatus();
        }

        public String body() {
            try {
                return result.getResponse().getContentAsString();
            } catch (java.io.UnsupportedEncodingException e) {
                throw new IllegalStateException(e);
            }
        }

        public <T> T as(Class<T> type) {
            return mapper.readValue(body(), type);
        }

        public <T> T as(TypeReference<T> type) {
            return mapper.readValue(body(), type);
        }

        public String message() {
            return mapper.readTree(body()).get("message").asString();
        }
    }
}
