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

    private MvcTestResult post(String path, String body) {
        return mvc.post().uri(path).header(HttpHeaders.AUTHORIZATION, bearer())
            .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private String bearer() {
        return "Bearer " + TestTokens.issuer(Instant.now()).issueAccessToken(TestTokens.MEMBER_ID, MemberRole.MANAGER).tokenValue();
    }
}
