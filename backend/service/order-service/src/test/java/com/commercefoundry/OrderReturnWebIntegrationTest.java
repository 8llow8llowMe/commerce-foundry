package com.commercefoundry;

import static com.commercefoundry.domainlayer.order.support.OrderFixtures.*;
import static org.assertj.core.api.Assertions.*;

import com.commercefoundry.domainlayer.order.application.port.out.OrderRepositoryPort;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatusHistory;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.support.TestTokens;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 실제 JWT·메서드 권한·검증·Facade·DB 접수를 관통하는 고객 계약. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class OrderReturnWebIntegrationTest {
    @Autowired MockMvcTester mvc;
    @Autowired OrderRepositoryPort orders;
    @Autowired SnowflakeIdGenerator ids;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    private Order create() {
        Order order = Order.place(ids.generateId(), TestTokens.MEMBER_ID, domesticQuote(quoteLine(101, 1000, 3)), ids::generateId,
            address("KR"), ids.generateId(), Instant.now().plusSeconds(600));
        new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
            orders.insert(order, OrderStatusHistory.created(ids.generateId(), order));
            jdbc.update("update orders.orders set status = 'DELIVERED', version = version + 1 where id = ?", order.id());
        });
        return order;
    }

    private String body(Order order, int quantity) {
        return "{\"items\":[{\"orderItemId\":\"" + order.itemIds().getFirst() + "\",\"quantity\":" + quantity + ",\"reason\":\"DEFECTIVE\"}]}";
    }

    private MvcTestResult post(long orderId, String key, String body, MemberRole role, long member) {
        var request = mvc.post().uri("/api/v1/orders/{orderId}/returns", orderId).contentType(MediaType.APPLICATION_JSON).content(body);
        if (role != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(member, role).tokenValue());
        }
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return request.exchange();
    }

    @Test
    void customerCreatesAndReplaysWithUppercaseKeyAndMetadataStringIdsAndNullRefund() throws Exception {
        Order order = create();
        String key = UUID.randomUUID().toString();
        var first = post(order.id(), key, body(order, 1), MemberRole.CUSTOMER, TestTokens.MEMBER_ID);
        assertThat(first).hasStatus(201);
        assertThat(first).bodyJson().extractingPath("$.dataBody.orderId").isEqualTo(String.valueOf(order.id()));
        assertThat(first).bodyJson().extractingPath("$.dataBody.items[0].orderItemId").isEqualTo(String.valueOf(order.itemIds().getFirst()));
        assertThat(first).bodyJson().extractingPath("$.dataBody.status.code").isEqualTo("REQUESTED");
        assertThat(first).bodyJson().extractingPath("$.dataBody.items[0].reason.code").isEqualTo("DEFECTIVE");
        assertThat(first).bodyJson().extractingPath("$.dataBody.expectedRefund").isNull();
        assertThat(first).bodyJson().doesNotHavePath("$.dataBody.created");
        var replay = post(order.id(), key.toUpperCase(), body(order, 1), MemberRole.CUSTOMER, TestTokens.MEMBER_ID);
        assertThat(replay).hasStatus(200);
        assertThat(replay).bodyJson().isEqualTo(first.getResponse().getContentAsString());
        assertThat(post(order.id(), key, body(order, 2), MemberRole.CUSTOMER, TestTokens.MEMBER_ID)).hasStatus(409);
        assertThat(post(order.id(), key, body(order, 1), MemberRole.CUSTOMER, TestTokens.MEMBER_ID + 1)).hasStatus(404);
        assertThat(post(Long.MAX_VALUE, key, body(order, 1), MemberRole.CUSTOMER, TestTokens.MEMBER_ID)).hasStatus(404);
    }

    @Test
    void anonymousAndAdminCannotSubmitCustomerReturn() {
        Order order = create();
        String key = UUID.randomUUID().toString();
        assertThat(post(order.id(), key, body(order, 1), null, 0)).hasStatus(401);
        assertThat(post(order.id(), key, body(order, 1), MemberRole.ADMIN, TestTokens.MEMBER_ID)).hasStatus(403);
    }

    @Test
    void requiredAndCanonicalHeaderValidationUses198And199() {
        Order order = create();
        var missing = post(order.id(), null, body(order, 1), MemberRole.CUSTOMER, TestTokens.MEMBER_ID);
        assertThat(missing).hasStatus(400);
        assertThat(missing).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("ORDER_199");
        for (String key : new String[]{"1-1-1-1-1", " 3f2b8c1e-6a4d-4e2f-9b1a-7c5d2e8f0a11", "invalid", "", UUID.randomUUID() + "," + UUID.randomUUID()}) {
            var result = post(order.id(), key, body(order, 1), MemberRole.CUSTOMER, TestTokens.MEMBER_ID);
            assertThat(result).hasStatus(400);
            assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("ORDER_198");
        }
        String token = TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, MemberRole.CUSTOMER).tokenValue();
        var multiple = mvc.post().uri("/api/v1/orders/{orderId}/returns", order.id()).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .header("Idempotency-Key", UUID.randomUUID().toString(), UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content(body(order, 1)).exchange();
        assertThat(multiple).hasStatus(400);
        assertThat(multiple).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("ORDER_198");
    }

    @Test
    void bodyConstraintsKeepItemFieldPaths() {
        Order order = create();
        String key = UUID.randomUUID().toString();
        for (String body : new String[]{"{\"items\":[]}", "{\"items\":[null]}", "{\"items\":[{}]}", body(order, 0), body(order, 100),
            body(order, 1).replace("DEFECTIVE", "OTHER"), body(order, 1).replace(String.valueOf(order.itemIds().getFirst()), "-1")}) {
            assertThat(post(order.id(), key, body, MemberRole.CUSTOMER, TestTokens.MEMBER_ID)).hasStatus(400);
        }
        var invalid = post(order.id(), key, "{\"items\":[{}]}", MemberRole.CUSTOMER, TestTokens.MEMBER_ID);
        assertThat(invalid).bodyJson().extractingPath("$.dataHeader.fieldErrors[*].field").asArray()
            .contains("items[0].orderItemId", "items[0].quantity", "items[0].reason");
    }

    @Test
    void invalidHeaderAndBodyPreserveBothFieldPathsAndCodes() {
        Order order = create();
        var invalid = post(order.id(), "invalid", "{\"items\":[{}]}", MemberRole.CUSTOMER, TestTokens.MEMBER_ID);
        assertThat(invalid).hasStatus(400);
        assertThat(invalid).bodyJson().extractingPath("$.dataHeader.fieldErrors[*].field").asArray()
            .contains("requestKey", "items[0].orderItemId", "items[0].quantity", "items[0].reason");
        assertThat(invalid).bodyJson().extractingPath("$.dataHeader.fieldErrors[*].code").asArray().contains("ORDER_198");
    }

    @Nested
    @TestPropertySource(properties = {"springdoc.api-docs.enabled=true", "springdoc.swagger-ui.enabled=true"})
    class SwaggerContract {
        @Autowired MockMvcTester docs;

        @Test
        void actualCustomerDocumentDescribesHeaderAndSeparateInputOutputSchemas() throws Exception {
            var result = docs.get().uri("/v3/api-docs/customer").exchange();
            assertThat(result).hasStatus(200);
            assertThat(result).bodyJson().hasPath("$.paths['/api/v1/orders/{orderId}/returns'].post");
            assertThat(result).bodyJson().extractingPath("$.paths['/api/v1/orders/{orderId}/returns'].post.parameters[?(@.name=='Idempotency-Key')].required")
                .asArray().containsExactly(true);
            assertThat(result).bodyJson().extractingPath("$.components.schemas.OrderReturnResponse.properties.orderId.type").isEqualTo("string");
            assertThat(result).bodyJson().extractingPath("$.components.schemas.OrderReturnResponse.properties.claimId.type").isEqualTo("string");
            assertThat(result).bodyJson().hasPath("$.components.schemas.OrderReturnResponse.properties.expectedRefund");
            var refundSchema = tools.jackson.databind.json.JsonMapper.builder().build().readTree(result.getResponse().getContentAsString())
                .path("components").path("schemas").path("OrderReturnResponse").path("properties").path("expectedRefund").toString();
            assertThat(refundSchema.contains("\"nullable\":true") || refundSchema.contains("\"null\""))
                .as("OpenAPI 3.0 nullable 또는 3.1 null 타입이 있어야 한다: %s", refundSchema).isTrue();
            assertThat(result).bodyJson().doesNotHavePath("$.components.schemas.OrderReturnResponse.properties.created");
            assertThat(result).bodyJson().extractingPath("$.components.schemas.OrderReturnRequestItem.properties.orderItemId.type").isEqualTo("integer");
            assertThat(result).bodyJson().extractingPath("$.components.schemas.OrderReturnResponseItem.properties.orderItemId.type").isEqualTo("string");
            assertThat(result).bodyJson().extractingPath("$.components.schemas.OrderReturnRequestItem.properties.reason.enum").asArray()
                .containsExactly("CHANGE_OF_MIND", "DEFECTIVE", "WRONG_ITEM");
        }
    }
}
