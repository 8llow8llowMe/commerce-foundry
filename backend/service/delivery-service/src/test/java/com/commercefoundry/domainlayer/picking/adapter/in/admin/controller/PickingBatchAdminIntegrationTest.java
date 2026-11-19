package com.commercefoundry.domainlayer.picking.adapter.in.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.commercefoundry.domainlayer.picking.application.port.out.OrderPickingSnapshotQueryPort;
import com.commercefoundry.domainlayer.picking.domain.model.PickingSnapshot;
import com.commercefoundry.security.audit.AdminAuditLog;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.support.TestTokens;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** HTTP 진입부터 묶음 저장·재조회까지 실제 빈을 사용하고 원격 주문 조회만 대체한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:delivery-picking-api;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
    + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS delivery")
class PickingBatchAdminIntegrationTest {

    private static final MediaType CSV = MediaType.parseMediaType("text/csv");

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private OrderPickingSnapshotQueryPort snapshots;

    @Test
    void persistsExclusionsAndCheckHistoryWithoutDuplicatingRetries() {
        List<Long> orderIds = List.of(101L, 102L);
        given(snapshots.findAll(orderIds)).willReturn(List.of(
            new PickingSnapshot(101, "PREPARING", "KR", List.of(new PickingSnapshot.Line(10, 2, false))),
            new PickingSnapshot(102, "CANCELED", "KR", List.of(new PickingSnapshot.Line(10, 1, false)))
        ));

        Logger auditLogger = (Logger) LoggerFactory.getLogger(AdminAuditLog.LOGGER_NAME);
        ListAppender<ILoggingEvent> audit = new ListAppender<>();
        audit.start();
        auditLogger.addAppender(audit);
        try {
            var created = post(PickingBatchAdminController.PATH, """
                {"orderIds":["101","102"]}
                """);
            assertThat(created).hasStatus(201);
            assertThat(created).bodyJson().extractingPath("$.dataBody.exclusions[0].reason.code").isEqualTo("CANCELED_OR_EXPIRED");
            assertThat(created).bodyJson().extractingPath("$.dataBody.totalQuantity").isEqualTo(2);
            String detail = created.getResponse().getHeader(HttpHeaders.LOCATION);
            assertThat(detail).startsWith(PickingBatchAdminController.PATH + "/");
            String batchId = detail.substring(detail.lastIndexOf('/') + 1);
            String check = detail + "/orders/101/checks";

            var wrongSku = post(check, """
                {"quantities":{"11":2}}
                """);
            assertThat(wrongSku).hasStatusOk();
            assertThat(wrongSku).bodyJson().extractingPath("$.dataBody.checks[0].reason.code").isEqualTo("WRONG_SKU");
            var shortage = post(check, """
                {"quantities":{}}
                """);
            assertThat(shortage).hasStatusOk();
            assertThat(shortage).bodyJson().extractingPath("$.dataBody.checks[1].reason.code").isEqualTo("SHORTAGE");
            assertThat(shortage).bodyJson().extractingPath("$.dataBody.inspectionComplete").isEqualTo(false);
            String correct = """
                {"quantities":{"10":2}}
                """;
            var passed = post(check, correct);
            assertThat(passed).hasStatusOk();
            assertThat(passed).bodyJson().extractingPath("$.dataBody.checks[2].reason.code").isEqualTo("NONE");
            assertThat(passed).bodyJson().extractingPath("$.dataBody.inspectionComplete").isEqualTo(true);
            var retried = post(check, correct);
            assertThat(retried).hasStatusOk();
            assertThat(retried).bodyJson().extractingPath("$.dataBody.checks.length()").isEqualTo(3);

            var fetched = mvc.get().uri(detail).header(HttpHeaders.AUTHORIZATION, bearer()).exchange();
            assertThat(fetched).hasStatusOk();
            assertThat(fetched).bodyJson().extractingPath("$.dataBody.exclusions[0].orderId").isEqualTo("102");
            assertThat(fetched).bodyJson().extractingPath("$.dataBody.checks.length()").isEqualTo(3);
            assertThat(fetched).bodyJson().extractingPath("$.dataBody.orders[0].checked").isEqualTo(true);

            String target = " actorMemberId=" + TestTokens.MEMBER_ID + " target=picking-batch:" + batchId;
            assertThat(audit.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly(
                "action=PICKING_BATCH_CREATE" + target + " result=SUCCESS",
                "action=PICKING_ORDER_CHECK" + target + " result=FAILURE",
                "action=PICKING_ORDER_CHECK" + target + " result=FAILURE",
                "action=PICKING_ORDER_CHECK" + target + " result=SUCCESS",
                "action=PICKING_ORDER_CHECK" + target + " result=SUCCESS"
            );
            verify(snapshots).findAll(orderIds);
            verifyNoMoreInteractions(snapshots);
        } finally {
            auditLogger.detachAppender(audit);
            audit.stop();
        }
    }

    @Test
    void downloadsOnlyPickingQuantitiesWithReadPermissionAndAuditsTheOutcome() {
        long orderId = 4611686018427387904L;
        long skuId = 4611686018427387905L;
        given(snapshots.findAll(List.of(orderId, 102L, 103L))).willReturn(List.of(
            new PickingSnapshot(orderId, "PREPARING", "KR", List.of(new PickingSnapshot.Line(skuId, 2, false))),
            new PickingSnapshot(102, "PREPARING", "KR",
                List.of(new PickingSnapshot.Line(skuId, 3, false), new PickingSnapshot.Line(10, 1, false))),
            new PickingSnapshot(103, "CANCELED", "KR", List.of(new PickingSnapshot.Line(skuId, 100, false)))
        ));

        var created = post(PickingBatchAdminController.PATH, """
            {"orderIds":["4611686018427387904","102","103"]}
            """);
        assertThat(created).hasStatus(201);
        String detail = created.getResponse().getHeader(HttpHeaders.LOCATION);
        assertThat(detail).startsWith(PickingBatchAdminController.PATH + "/");
        String batchId = detail.substring(detail.lastIndexOf('/') + 1);
        String download = detail + "/picking-list";

        Logger auditLogger = (Logger) LoggerFactory.getLogger(AdminAuditLog.LOGGER_NAME);
        ListAppender<ILoggingEvent> audit = new ListAppender<>();
        audit.start();
        auditLogger.addAppender(audit);
        try {
            var json = mvc.get().uri(detail).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.STAFF)).exchange();
            assertThat(json).hasStatusOk();
            assertThat(json).bodyJson().extractingPath("$.dataBody.totalQuantity").isEqualTo(6);
            assertThat(json).bodyJson().extractingPath("$.dataBody.pickingQuantities[1].quantity").isEqualTo(5);

            var file = mvc.get().uri(download).accept(CSV).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.STAFF)).exchange();
            assertThat(file).hasStatusOk().hasHeader(HttpHeaders.CACHE_CONTROL, "no-store")
                .hasHeader(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8")
                .hasHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"picking-list-" + batchId + ".csv\"");
            String expected = "\uFEFF" + """
                구분,출고 묶음,주문,상품 옵션,꺼낼 수량
                합계,#%1$s,,#10,1
                합계,#%1$s,,#4611686018427387905,5
                주문별,#%1$s,#102,#10,1
                주문별,#%1$s,#102,#4611686018427387905,3
                주문별,#%1$s,#4611686018427387904,#4611686018427387905,2
                """.formatted(batchId).replace("\n", "\r\n");
            assertThat(file.getResponse().getContentAsByteArray()).containsExactly(expected.getBytes(StandardCharsets.UTF_8));

            var missing = mvc.get().uri(PickingBatchAdminController.PATH + "/999/picking-list").accept(CSV)
                .header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.STAFF)).exchange();
            assertThat(missing).hasStatus(404).hasHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
            assertThat(missing.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION)).isNull();
            assertThat(missing).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("DELIVERY_034");

            var invalidId = mvc.get().uri(PickingBatchAdminController.PATH + "/invalid/picking-list").accept(CSV, MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.STAFF)).exchange();
            assertThat(invalidId).hasStatus(400);
            assertThat(invalidId).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("DELIVERY_198");

            var anonymous = mvc.get().uri(download).accept(CSV).exchange();
            assertThat(anonymous).hasStatus(401);
            assertThat(anonymous).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("SECURITY_001");
            var customer = mvc.get().uri(download).accept(CSV).header(HttpHeaders.AUTHORIZATION, bearer(MemberRole.CUSTOMER)).exchange();
            assertThat(customer).hasStatus(403);
            assertThat(customer).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo("SECURITY_005");

            String actor = "action=PICKING_LIST_DOWNLOAD actorMemberId=" + TestTokens.MEMBER_ID + " target=picking-batch:";
            assertThat(audit.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly(
                actor + batchId + " result=SUCCESS",
                actor + "999 result=FAILURE"
            );
        } finally {
            auditLogger.detachAppender(audit);
            audit.stop();
        }
    }

    private MvcTestResult post(String path, String body) {
        return mvc.post().uri(path).header(HttpHeaders.AUTHORIZATION, bearer())
            .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private String bearer() {
        return bearer(MemberRole.MANAGER);
    }

    private String bearer(MemberRole role) {
        return "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, role).tokenValue();
    }
}
