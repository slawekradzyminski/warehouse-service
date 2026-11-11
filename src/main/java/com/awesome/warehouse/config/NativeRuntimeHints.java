package com.awesome.warehouse.config;

import com.awesome.warehouse.client.EventFeedPage;
import com.awesome.warehouse.client.FeedEvent;
import com.awesome.warehouse.client.FulfilmentUpdateRequest;
import com.awesome.warehouse.client.InternalOrderDto;
import com.awesome.warehouse.client.InternalProductDto;
import com.awesome.warehouse.client.InventoryReceiptRequest;
import com.awesome.warehouse.client.InventoryReceiptResponse;
import com.awesome.warehouse.client.NotificationRequest;
import com.awesome.warehouse.client.OrderRefundRequest;
import com.awesome.warehouse.client.JwkSet;
import com.awesome.warehouse.dto.ErrorDto;
import com.awesome.warehouse.dto.order.PageDto;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

import java.util.List;

/**
 * GraalVM native-image hints. Spring AOT evaluates these at build time; the
 * running image does no classpath scanning.
 *
 * <ul>
 *   <li>Internal-API records are (de)serialised by Jackson through {@code RestClient},
 *       which AOT cannot infer from controller signatures.</li>
 *   <li>JJWT's API module instantiates its implementation classes by name
 *       ({@code Classes.newInstance("io.jsonwebtoken.impl...")}); exactly those
 *       classes (and the Jackson (de)serializers found through ServiceLoader)
 *       are registered for reflective construction.</li>
 *   <li>Hibernate's multi-id loader instantiates an array of each entity's id type
 *       reflectively; {@code UUID[]} ({@code processed_events}) and {@code String[]}
 *       ({@code event_cursors}) are not covered by Spring's JPA hints.</li>
 *   <li>Flyway migrations and the Postgres driver are covered by Spring Boot's
 *       hints and the GraalVM reachability-metadata repository
 *       ({@code add-reachability-metadata} in the {@code native} profile).</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@ImportRuntimeHints(NativeRuntimeHints.Registrar.class)
@RegisterReflectionForBinding({
    InternalOrderDto.class,
    InternalProductDto.class,
    FulfilmentUpdateRequest.class,
    InventoryReceiptRequest.class,
    InventoryReceiptResponse.class,
    NotificationRequest.class,
    OrderRefundRequest.class,
    JwkSet.class,
    JwkSet.Jwk.class,
    EventFeedPage.class,
    FeedEvent.class,
    PageDto.class,
    ErrorDto.class
})
public class NativeRuntimeHints {

    static class Registrar implements RuntimeHintsRegistrar {

        /** Implementation classes that jjwt-api loads by name (see {@code io.jsonwebtoken.lang.Classes}). */
        static final List<String> JJWT_REFLECTIVE_TYPES = List.of(
                "io.jsonwebtoken.impl.DefaultClaimsBuilder$Supplier",
                "io.jsonwebtoken.impl.DefaultJwtBuilder$Supplier",
                "io.jsonwebtoken.impl.DefaultJwtHeaderBuilder$Supplier",
                "io.jsonwebtoken.impl.DefaultJwtParserBuilder$Supplier",
                "io.jsonwebtoken.impl.io.StandardCompressionAlgorithms",
                "io.jsonwebtoken.impl.security.DefaultDynamicJwkBuilder$Supplier",
                "io.jsonwebtoken.impl.security.DefaultJwkParserBuilder$Supplier",
                "io.jsonwebtoken.impl.security.DefaultJwkSetBuilder$Supplier",
                "io.jsonwebtoken.impl.security.DefaultJwkSetParserBuilder$Supplier",
                "io.jsonwebtoken.impl.security.DefaultKeyOperationBuilder$Supplier",
                "io.jsonwebtoken.impl.security.DefaultKeyOperationPolicyBuilder$Supplier",
                "io.jsonwebtoken.impl.security.JwksBridge",
                "io.jsonwebtoken.impl.security.KeysBridge",
                "io.jsonwebtoken.impl.security.StandardCurves",
                "io.jsonwebtoken.impl.security.StandardEncryptionAlgorithms",
                "io.jsonwebtoken.impl.security.StandardHashAlgorithms",
                "io.jsonwebtoken.impl.security.StandardKeyAlgorithms",
                "io.jsonwebtoken.impl.security.StandardKeyOperations",
                "io.jsonwebtoken.impl.security.StandardSecureDigestAlgorithms",
                "io.jsonwebtoken.jackson.io.JacksonDeserializer",
                "io.jsonwebtoken.jackson.io.JacksonSerializer");

        /** Id array types Hibernate instantiates reflectively (see the class comment). */
        static final List<Class<?>> ENTITY_ID_ARRAYS = List.of(java.util.UUID[].class, String[].class);

        @Override
        public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
            for (String type : JJWT_REFLECTIVE_TYPES) {
                hints.reflection().registerType(TypeReference.of(type), MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
            }
            for (Class<?> idArray : ENTITY_ID_ARRAYS) {
                hints.reflection().registerType(idArray);
            }
            hints.resources().registerPattern("db/migration/*.sql");
        }
    }
}
