package com.commercefoundry.domainlayer.picking.adapter.in.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.commercefoundry.domainlayer.picking.adapter.out.persistence.PickingBatchRepositoryAdapter;
import com.commercefoundry.domainlayer.picking.application.port.out.OrderPickingSnapshotQueryPort;
import com.commercefoundry.domainlayer.picking.application.service.processor.PickingBatchProcessor;
import com.commercefoundry.domainlayer.picking.domain.model.PickingCheck;
import com.commercefoundry.domainlayer.picking.domain.model.PickingSnapshot;
import com.commercefoundry.security.audit.AdminAuditLog;
import com.commercefoundry.security.principal.MemberRole;
import com.commercefoundry.security.support.TestTokens;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:delivery-picking-bulk;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
    + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS delivery")
class PickingBulkCheckIntegrationTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private PickingBatchProcessor batches;

    @MockitoBean
    private OrderPickingSnapshotQueryPort snapshots;

    @MockitoSpyBean
    private PickingBatchRepositoryAdapter repository;

    @Test
    void keepsOrderResultsAndReusesFailuresUntilTheQuantitiesAreCorrected() {
        long firstOrder = 4611686018427387904L;
        String detail = createBatch(firstOrder, 202, 203, 204, 205);
        long batchId = batchId(detail);
        String firstItem = item(firstOrder, 10, 2);
        String shortage = item(202, 10, 1);
        String wrongSku = item(203, 99, 2);
        String excess = item(204, 10, 3);
        String outside = item(999, 10, 2);

        Logger auditLogger = (Logger) LoggerFactory.getLogger(AdminAuditLog.LOGGER_NAME);
        ListAppender<ILoggingEvent> audit = new ListAppender<>();
        audit.start();
        auditLogger.addAppender(audit);
        try {
            var partial = bulk(detail, firstItem, shortage, wrongSku, excess, outside);
            assertThat(partial).hasStatusOk();
            assertThat(partial).bodyJson().extractingPath("$.dataBody.batchId").isEqualTo(Long.toString(batchId));
            assertThat(partial).bodyJson().extractingPath("$.dataBody.results[*].orderId")
                .isEqualTo(List.of(Long.toString(firstOrder), "202", "203", "204", "999"));
            assertReasons(partial, "NONE", "SHORTAGE", "WRONG_SKU", "EXCESS", "ORDER_NOT_IN_BATCH");
            assertThat(partial).bodyJson().extractingPath("$.dataBody.results[*].successful")
                .isEqualTo(List.of(true, false, false, false, false));
            assertThat(partial).bodyJson().extractingPath("$.dataBody.results[*].retryable")
                .isEqualTo(List.of(false, false, false, false, false));
            assertThat(batches.detail(batchId).checks()).hasSize(5);

            assertReasons(bulk(detail, shortage), "SHORTAGE");
            assertThat(batches.detail(batchId).checks()).hasSize(5);
            assertReasons(bulk(detail, item(202, 10, 2)), "NONE");
            assertReasons(bulk(detail, item(202, 10, 2)), "NONE");
            assertThat(batches.detail(batchId).checks()).hasSize(6);
            assertReasons(bulk(detail, item(firstOrder, 10, 1)), "ALREADY_CHECKED");
            assertThat(batches.detail(batchId).checked(firstOrder)).isTrue();

            String prefix = "action=PICKING_BULK_CHECK actorMemberId=" + TestTokens.MEMBER_ID + " target=picking-batch:" + batchId;
            assertThat(audit.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly(
                prefix + " result=FAILURE", prefix + " result=FAILURE", prefix + " result=SUCCESS",
                prefix + " result=SUCCESS", prefix + " result=FAILURE"
            );
        } finally {
            auditLogger.detachAppender(audit);
            audit.stop();
        }
    }

    @Test
    void rejectsDuplicateOrdersAndMoreRowsThanTheBatchBeforeWriting() {
        String detail = createBatch(401, 402);
        String first = item(401, 10, 2);

        assertCode(bulk(detail, first, item(401, 10, 1)), 400, "DELIVERY_038");
        assertCode(bulk(detail, first, item(402, 10, 2), item(403, 10, 2)), 400, "DELIVERY_038");
        assertCode(bulk(PickingBatchAdminController.PATH + "/999", first), 404, "DELIVERY_034");
        assertThat(batches.detail(batchId(detail)).checks()).isEmpty();
    }

    @Test
    void rollsBackOnlyTheContendingOrderAndCommitsOrdersOnBothSides() {
        String detail = createBatch(301, 302, 303, 304);
        long batchId = batchId(detail);
        AtomicBoolean raced = new AtomicBoolean();
        doAnswer(invocation -> {
            if (raced.compareAndSet(false, true)) {
                // 일괄 요청이 302의 쓰기에 들어가기 직전, 같은 묶음 버전을 별도 스레드에서 먼저 갱신한다.
                try (var executor = Executors.newSingleThreadExecutor()) {
                    executor.submit(() -> {
                        assertThat(batches.detail(batchId).checked(301)).isTrue();
                        batches.check(batchId, 304, Map.of(10L, 2L));
                    }).get(8, TimeUnit.SECONDS);
                }
            }
            return invocation.callRealMethod();
        }).when(repository).addCheck(argThat(batch -> batch.id() == batchId && batch.checks().getLast().orderId() == 302L));

        var partial = bulk(detail, item(301, 10, 2), item(302, 10, 2), item(303, 10, 2));
        assertReasons(partial, "NONE", "DELIVERY_037", "NONE");
        assertThat(partial).bodyJson().extractingPath("$.dataBody.results[*].successful").isEqualTo(List.of(true, false, true));
        assertThat(partial).bodyJson().extractingPath("$.dataBody.results[*].retryable").isEqualTo(List.of(false, true, false));
        assertThat(batches.detail(batchId).checks()).extracting(PickingCheck::orderId).containsExactly(301L, 304L, 303L);

        assertReasons(bulk(detail, item(302, 10, 2)), "NONE");
        assertReasons(bulk(detail, item(302, 10, 2)), "NONE");
        assertThat(batches.detail(batchId).checks()).extracting(PickingCheck::orderId).containsExactly(301L, 304L, 303L, 302L);
    }

    private String createBatch(long... ids) {
        List<Long> orderIds = Arrays.stream(ids).boxed().toList();
        given(snapshots.findAll(orderIds)).willReturn(orderIds.stream().map(id ->
            new PickingSnapshot(id, "PREPARING", "KR", List.of(new PickingSnapshot.Line(10, 2, false)))
        ).toList());
        List<String> quotedIds = orderIds.stream().map(id -> "\"%d\"".formatted(id)).toList();
        var created = post(PickingBatchAdminController.PATH, "{\"orderIds\":[" + String.join(",", quotedIds) + "]}");
        assertThat(created).hasStatus(201);
        return created.getResponse().getHeader(HttpHeaders.LOCATION);
    }

    private long batchId(String detail) {
        return Long.parseLong(detail.substring(detail.lastIndexOf('/') + 1));
    }

    private String item(long orderId, long skuId, long quantity) {
        return """
            {"orderId":"%d","check":{"quantities":{"%d":%d}}}
            """.formatted(orderId, skuId, quantity);
    }

    private MvcTestResult bulk(String detail, String... items) {
        return post(detail + "/checks", "{\"items\":[" + String.join(",", items) + "]}");
    }

    private MvcTestResult post(String path, String body) {
        String token = TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, MemberRole.MANAGER).tokenValue();
        return mvc.post().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private void assertReasons(MvcTestResult result, String... reasons) {
        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.dataBody.results[*].reason.code").isEqualTo(List.of(reasons));
    }

    private void assertCode(MvcTestResult result, int status, String code) {
        assertThat(result).hasStatus(status);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isEqualTo(code);
    }
}
