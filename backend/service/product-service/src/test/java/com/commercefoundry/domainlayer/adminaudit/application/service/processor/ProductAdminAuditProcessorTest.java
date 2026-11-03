package com.commercefoundry.domainlayer.adminaudit.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.commercefoundry.security.audit.AdminAuditLog;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * product 어드민 쓰기 감사 줄의 모양 — 행위 이름 · 행위자 · 대상(리소스 종류) · 결과 (#163, {@code security-guide.md} §11-4).
 * 트랜잭션 밖에서 부르므로 줄이 바로 남는다. 트랜잭션 결과 반영은 {@code CategoryAdminAuditIntegrationTest} 가 본다.
 */
class ProductAdminAuditProcessorTest {

    private static final long ACTOR = 4_611_686_018_427_387_904L;

    private final ProductAdminAuditProcessor processor = new ProductAdminAuditProcessor();
    private final Logger auditLogger = (Logger) LoggerFactory.getLogger(AdminAuditLog.LOGGER_NAME);
    private ListAppender<ILoggingEvent> audit;

    @BeforeEach
    void attach() {
        audit = new ListAppender<>();
        audit.start();
        auditLogger.addAppender(audit);
    }

    @AfterEach
    void detach() {
        auditLogger.detachAppender(audit);
    }

    @Test
    @DisplayName("카테고리 생성 · 수정 · 삭제는 target=category:<id> 이고, 만들지 못한 생성은 target=- 다")
    void category() {
        processor.categoryCreated(ACTOR, 12L, true);
        processor.categoryCreated(ACTOR, null, false);
        processor.categoryUpdated(ACTOR, 12L, false);
        processor.categoryDeleted(ACTOR, 12L, true);

        assertThat(lines()).containsExactly(
            "action=CATEGORY_CREATE actorMemberId=" + ACTOR + " target=category:12 result=SUCCESS",
            "action=CATEGORY_CREATE actorMemberId=" + ACTOR + " target=- result=FAILURE",
            "action=CATEGORY_UPDATE actorMemberId=" + ACTOR + " target=category:12 result=FAILURE",
            "action=CATEGORY_DELETE actorMemberId=" + ACTOR + " target=category:12 result=SUCCESS"
        );
    }

    @Test
    @DisplayName("사이즈표 템플릿은 target=size-chart-template:<id>, 상품 사이즈표 교체는 target=product:<productId> 다")
    void sizeChart() {
        processor.sizeChartTemplateCreated(ACTOR, 7L, true);
        processor.sizeChartTemplateCreated(ACTOR, null, false);
        processor.sizeChartTemplateUpdated(ACTOR, 7L, true);
        processor.sizeChartTemplateDeleted(ACTOR, 7L, false);
        processor.productSizeChartReplaced(ACTOR, 700L, true);

        assertThat(lines()).containsExactly(
            "action=SIZE_CHART_TEMPLATE_CREATE actorMemberId=" + ACTOR + " target=size-chart-template:7 result=SUCCESS",
            "action=SIZE_CHART_TEMPLATE_CREATE actorMemberId=" + ACTOR + " target=- result=FAILURE",
            "action=SIZE_CHART_TEMPLATE_UPDATE actorMemberId=" + ACTOR + " target=size-chart-template:7 result=SUCCESS",
            "action=SIZE_CHART_TEMPLATE_DELETE actorMemberId=" + ACTOR + " target=size-chart-template:7 result=FAILURE",
            "action=PRODUCT_SIZE_CHART_REPLACE actorMemberId=" + ACTOR + " target=product:700 result=SUCCESS"
        );
    }

    @Test
    @DisplayName("줄은 admin.audit 로거에 INFO 로 남는다")
    void infoLevel() {
        processor.categoryDeleted(ACTOR, 1L, true);

        assertThat(audit.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getLoggerName()).isEqualTo(AdminAuditLog.LOGGER_NAME);
        });
    }

    @Test
    void categoryReplacementHasItsOwnActionAndProductTarget() {
        processor.productCategoriesReplaced(ACTOR, 700L, true);
        processor.productCategoriesReplaced(ACTOR, 700L, false);
        assertThat(lines()).containsExactly(
            "action=PRODUCT_CATEGORIES_REPLACE actorMemberId=" + ACTOR + " target=product:700 result=SUCCESS",
            "action=PRODUCT_CATEGORIES_REPLACE actorMemberId=" + ACTOR + " target=product:700 result=FAILURE"
        );
    }

    @Test
    void mediaImageUploadsContainOnlyFourAuditFieldsAndInternalUuid() {
        var id = java.util.UUID.fromString("11111111-1111-1111-1111-111111111111");
        processor.mediaImageUploadIssued(ACTOR, id, true);
        processor.mediaImageUploadIssued(ACTOR, null, false);
        processor.mediaImageUploadConfirmed(ACTOR, id, true);
        processor.mediaImageUploadConfirmed(ACTOR, id, false);
        assertThat(lines()).containsExactly(
            "action=MEDIA_IMAGE_UPLOAD_ISSUE actorMemberId=" + ACTOR + " target=media-upload:" + id + " result=SUCCESS",
            "action=MEDIA_IMAGE_UPLOAD_ISSUE actorMemberId=" + ACTOR + " target=- result=FAILURE",
            "action=MEDIA_IMAGE_UPLOAD_CONFIRM actorMemberId=" + ACTOR + " target=media-upload:" + id + " result=SUCCESS",
            "action=MEDIA_IMAGE_UPLOAD_CONFIRM actorMemberId=" + ACTOR + " target=media-upload:" + id + " result=FAILURE"
        );
    }

    private List<String> lines() {
        return audit.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }
}
