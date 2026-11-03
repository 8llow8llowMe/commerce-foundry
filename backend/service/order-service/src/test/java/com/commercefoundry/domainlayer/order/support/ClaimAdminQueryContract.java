package com.commercefoundry.domainlayer.order.support;

import static com.commercefoundry.domainlayer.order.support.OrderFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.commercefoundry.domainlayer.order.application.port.out.ClaimRepositoryPort;
import com.commercefoundry.domainlayer.order.application.port.out.OrderRepositoryPort;
import com.commercefoundry.domainlayer.order.domain.model.Claim;
import com.commercefoundry.domainlayer.order.domain.model.ClaimItem;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatusHistory;
import com.commercefoundry.security.audit.AdminAuditLog;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.support.TestTokens;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 실제 JWT·HTTP·DB에 동일한 계약을 적용한다. 전체 query 수에는 인증에서 발생한 조회도 포함한다. */
public abstract class ClaimAdminQueryContract {
    private static final AtomicLong IDS = new AtomicLong(4_611_686_018_430_000_000L);
    @Autowired protected MockMvc mvc;
    @Autowired private ClaimRepositoryPort claims;
    @Autowired private OrderRepositoryPort orders;
    @Autowired private PlatformTransactionManager manager;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired protected JdbcTemplate jdbc;

    private Statistics stats() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    protected MockHttpServletResponse request(String uri, MemberRole role) throws Exception {
        var request = get(uri);
        if (role != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, role).tokenValue());
        }
        return mvc.perform(request).andReturn().getResponse();
    }

    private void failure(String uri, MemberRole role, int status, String code) throws Exception {
        stats().clear();
        MockHttpServletResponse response = request(uri, role);
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getContentAsString()).contains("\"resultCode\":\"" + code + "\"");
        assertThat(stats().getPrepareStatementCount()).as("거절 요청은 조회하지 않는다").isZero();
    }

    @Test
    void rejectsBeforeReading() throws Exception {
        for (String uri : List.of("/api/v1/admin/claims", "/api/v1/admin/claims/1")) {
            failure(uri, null, 401, "SECURITY_001");
            failure(uri, MemberRole.CUSTOMER, 403, "SECURITY_005");
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "/api/v1/admin/claims?page=-1 | ORDER_144",
        "/api/v1/admin/claims?size=0 | ORDER_145",
        "/api/v1/admin/claims?size=101 | ORDER_145",
        "/api/v1/admin/claims?page=2147483647&size=100 | ORDER_167",
        "/api/v1/admin/claims?orderId=0 | ORDER_141",
        "/api/v1/admin/claims?type=CANCEL | ORDER_198",
        "/api/v1/admin/claims?status=APPROVED | ORDER_198",
        "/api/v1/admin/claims/0 | ORDER_166",
        "/api/v1/admin/claims/not-an-id | ORDER_198"
    })
    void invalidInputsDoNotRead(String uri, String code) throws Exception {
        failure(uri.strip(), MemberRole.ADMIN, 400, code.strip());
    }

    @Test
    void headerOnlyFiltersCountAndStableIdSort() throws Exception {
        Fixture f = fixture();
        List<Object> before = snapshot(f);
        String uri = "/api/v1/admin/claims?orderId=" + f.orderId() + "&type=RETURN&status=REQUESTED";
        stats().clear();
        var result = mvc.perform(get(uri).header(HttpHeaders.AUTHORIZATION, bearer())).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(stats().getPrepareStatementCount()).isEqualTo(2);
        jsonPath("$.dataBody.contents[0].claimId").value(String.valueOf(f.newer())).match(result);
        jsonPath("$.dataBody.contents[1].claimId").value(String.valueOf(f.older())).match(result);
        jsonPath("$.dataBody.contents[0].expectedRefund.amount").value(0).match(result);
        jsonPath("$.dataBody.contents[0].expectedRefund.currency").value("KRW").match(result);
        jsonPath("$.dataBody.contents[1].expectedRefund").value((Object) null).match(result);
        jsonPath("$.dataBody.totalElements").value(2).match(result);
        jsonPath("$.dataBody.page").value(0).match(result);
        jsonPath("$.dataBody.size").value(20).match(result);
        assertSafe(result.getResponse());
        stats().clear();
        var beyond = mvc.perform(get(uri + "&page=21474836&size=100").header(HttpHeaders.AUTHORIZATION, bearer())).andReturn();
        jsonPath("$.dataBody.contents").isEmpty().match(beyond);
        jsonPath("$.dataBody.totalElements").value(2).match(beyond);
        assertThat(stats().getPrepareStatementCount()).isEqualTo(2);
        stats().clear();
        var other = mvc.perform(get("/api/v1/admin/claims?orderId=" + IDS.incrementAndGet()).header(HttpHeaders.AUTHORIZATION, bearer())).andReturn();
        jsonPath("$.dataBody.totalElements").value(0).match(other);
        assertThat(stats().getPrepareStatementCount()).isEqualTo(2);
        assertThat(snapshot(f)).isEqualTo(before);
    }

    @Test
    void detailsHaveTwoQueriesRegardlessOfItemCountAndDoNotWrite() throws Exception {
        Fixture f = fixture();
        List<Object> before = snapshot(f);
        for (long id : List.of(f.older(), f.newer())) {
            stats().clear();
            var result = mvc.perform(get("/api/v1/admin/claims/" + id).header(HttpHeaders.AUTHORIZATION, bearer())).andReturn();
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(stats().getPrepareStatementCount()).isEqualTo(2);
            jsonPath("$.dataBody.claimId").value(String.valueOf(id)).match(result);
            jsonPath("$.dataBody.orderId").value(String.valueOf(f.orderId())).match(result);
            jsonPath("$.dataBody.items[0].orderItemId").isString().match(result);
            jsonPath("$.dataBody.items[0].reason.code").value("DEFECTIVE").match(result);
            jsonPath("$.dataBody.items.length()").value(id == f.older() ? 1 : 2).match(result);
            assertSafe(result.getResponse());
        }
        assertThat(snapshot(f)).isEqualTo(before);
        var missing = request("/api/v1/admin/claims/" + IDS.incrementAndGet(), MemberRole.ADMIN);
        assertThat(missing.getStatus()).isEqualTo(404);
        assertThat(missing.getContentAsString()).contains("ORDER_032");
    }

    @Test
    void auditContainsOnlyIdsAndFiltersAfterSuccessfulRead() throws Exception {
        Fixture f = fixture();
        Logger logger = (Logger) LoggerFactory.getLogger(AdminAuditLog.LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            request("/api/v1/admin/claims?orderId=" + f.orderId(), MemberRole.ADMIN);
            request("/api/v1/admin/claims/" + f.older(), MemberRole.ADMIN);
            request("/api/v1/admin/claims/" + IDS.incrementAndGet(), MemberRole.ADMIN);
            request("/api/v1/admin/claims/" + f.older(), MemberRole.CUSTOMER);
            assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly(
                "action=CLAIM_SEARCH actorMemberId=" + TestTokens.MEMBER_ID + " target=claims?orderId=" + f.orderId()
                    + "&type=ALL&status=ALL&page=0&size=20 result=SUCCESS",
                "action=CLAIM_VIEW actorMemberId=" + TestTokens.MEMBER_ID + " target=claim:" + f.older() + " result=SUCCESS");
            assertThat(appender.list).allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain("테스트", "010", "DEFECTIVE", "requestKey"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void swaggerContainsAdminContractOnly() throws Exception {
        var result = mvc.perform(get("/v3/api-docs/admin")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        jsonPath("$.paths['/api/v1/admin/claims'].get.parameters[*].name")
            .value(org.hamcrest.Matchers.containsInAnyOrder("orderId", "type", "status", "page", "size")).match(result);
        jsonPath("$.components.schemas.ClaimAdminItem.properties.claimId.type").value("string").match(result);
        jsonPath("$.components.schemas.ClaimAdminResponse.properties.items").exists().match(result);
        jsonPath("$.components.schemas.ClaimAdminLineItem.properties.reason").exists().match(result);
        assertThat(request("/v3/api-docs/customer", null).getContentAsString()).doesNotContain("/api/v1/admin/claims", "ClaimAdminResponse", "ClaimAdminItem");
    }

    private String bearer() {
        return "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, MemberRole.ADMIN).tokenValue();
    }

    private void assertSafe(MockHttpServletResponse response) throws Exception {
        assertThat(response.getContentAsString()).doesNotContain("requestKey", "version", "memberId", "recipient", "shippingAddress", "테스트수령인", "01000000001");
    }

    private List<Object> snapshot(Fixture f) {
        return List.of(jdbc.queryForList("select * from orders.orders where id = ?", f.orderId()),
            jdbc.queryForList("select * from orders.claim where order_id = ?", f.orderId()),
            jdbc.queryForList("select * from orders.claim_item where claim_id in (?, ?) order by id", f.older(), f.newer()),
            jdbc.queryForList("select * from orders.order_status_history where order_id = ? order by id", f.orderId()),
            jdbc.queryForObject("select count(*) from orders.outbox", Long.class));
    }

    private Fixture fixture() {
        long orderId = IDS.incrementAndGet();
        Order order = Order.place(orderId, TestTokens.MEMBER_ID, domesticQuote(quoteLine(101, 1000, 3), quoteLine(102, 2000, 2)),
            IDS::incrementAndGet, address("KR"), IDS.incrementAndGet(), Instant.now().plusSeconds(1800));
        long older = IDS.incrementAndGet();
        long newer = IDS.incrementAndGet();
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            orders.insert(order, OrderStatusHistory.created(IDS.incrementAndGet(), order));
            List<ClaimItem> lines = new ArrayList<>();
            lines.add(new ClaimItem(IDS.incrementAndGet(), order.itemIds().getFirst(), 1, ClaimItem.Reason.DEFECTIVE));
            claims.insert(new Claim(older, orderId, UUID.randomUUID(), Claim.Type.RETURN, Claim.Status.REQUESTED, null, lines, null, null));
            lines.add(new ClaimItem(IDS.incrementAndGet(), order.itemIds().getLast(), 1, ClaimItem.Reason.WRONG_ITEM));
            // claim_item IDs belong to one claim: use fresh IDs for the second claim.
            var second = lines.stream().map(i -> new ClaimItem(IDS.incrementAndGet(), i.orderItemId(), i.quantity(), i.reason())).toList();
            claims.insert(new Claim(newer, orderId, UUID.randomUUID(), Claim.Type.RETURN, Claim.Status.REQUESTED, krw(0), second, null, null));
            jdbc.update("update orders.claim set created_at = ? where order_id = ?", Timestamp.from(Instant.parse("2026-11-03T00:00:00Z")), orderId);
        });
        return new Fixture(orderId, older, newer);
    }

    private record Fixture(long orderId, long older, long newer) {
    }
}
