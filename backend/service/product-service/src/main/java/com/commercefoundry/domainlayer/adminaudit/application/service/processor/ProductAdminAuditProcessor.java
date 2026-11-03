package com.commercefoundry.domainlayer.adminaudit.application.service.processor;

import com.commercefoundry.domainlayer.adminaudit.application.model.ProductAdminAuditAction;
import com.commercefoundry.security.audit.AdminAuditLog;
import org.springframework.stereotype.Component;

/**
 * product-service 어드민 쓰기 감사 (#163). Facade 가 업무 성공 · 실패 뒤에 부르고, 줄은 security-core {@link AdminAuditLog} 가 쓴다.
 * 대상 문자열(행위별 리소스 종류)을 정하는 감사 정책을 여기 둔다 ({@code security-guide.md} §11-4).
 *
 * <p>Facade 트랜잭션 안에서 불려도 된다 — {@link AdminAuditLog} 가 트랜잭션이 끝난 뒤 커밋 결과로 기록한다(롤백이면 {@code FAILURE}).
 * 대상 식별자는 경로 변수처럼 업무 호출 전에 아는 값이라 업무가 예외로 끝나도 같은 대상으로 남는다. 생성은 실패하면 아이디가 없어 대상이 {@code -} 다.
 * 바꾼 값(이름 · 순서 · 치수 · 항목)은 싣지 않는다.
 */
@Component
public class ProductAdminAuditProcessor {

    static final String CATEGORY = "category";
    static final String SIZE_CHART_TEMPLATE = "size-chart-template";
    static final String PRODUCT = "product";

    public void productDeleted(long actorMemberId, long productId, boolean succeeded) {
        write(ProductAdminAuditAction.PRODUCT_DELETE, actorMemberId, AdminAuditLog.target(PRODUCT, productId), succeeded);
    }

    public void productUpdated(long actorMemberId, long productId, boolean succeeded) {
        write(ProductAdminAuditAction.PRODUCT_UPDATE, actorMemberId, AdminAuditLog.target(PRODUCT, productId), succeeded);
    }

    public void productCategoriesReplaced(long actorMemberId, long productId, boolean succeeded) {
        write(ProductAdminAuditAction.PRODUCT_CATEGORIES_REPLACE, actorMemberId, AdminAuditLog.target(PRODUCT, productId), succeeded);
    }

    /** @param categoryId 만든 카테고리 아이디 — 실패해 없으면 {@code null}({@code target=-}) */
    public void categoryCreated(long actorMemberId, Long categoryId, boolean succeeded) {
        write(ProductAdminAuditAction.CATEGORY_CREATE, actorMemberId, categoryId == null ? null : AdminAuditLog.target(CATEGORY, categoryId), succeeded);
    }

    public void categoryUpdated(long actorMemberId, long categoryId, boolean succeeded) {
        write(ProductAdminAuditAction.CATEGORY_UPDATE, actorMemberId, AdminAuditLog.target(CATEGORY, categoryId), succeeded);
    }

    public void categoryDeleted(long actorMemberId, long categoryId, boolean succeeded) {
        write(ProductAdminAuditAction.CATEGORY_DELETE, actorMemberId, AdminAuditLog.target(CATEGORY, categoryId), succeeded);
    }

    /** @param templateId 만든 템플릿 아이디 — 실패해 없으면 {@code null}({@code target=-}) */
    public void sizeChartTemplateCreated(long actorMemberId, Long templateId, boolean succeeded) {
        write(
            ProductAdminAuditAction.SIZE_CHART_TEMPLATE_CREATE, actorMemberId,
            templateId == null ? null : AdminAuditLog.target(SIZE_CHART_TEMPLATE, templateId), succeeded
        );
    }

    public void sizeChartTemplateUpdated(long actorMemberId, long templateId, boolean succeeded) {
        write(ProductAdminAuditAction.SIZE_CHART_TEMPLATE_UPDATE, actorMemberId, AdminAuditLog.target(SIZE_CHART_TEMPLATE, templateId), succeeded);
    }

    public void sizeChartTemplateDeleted(long actorMemberId, long templateId, boolean succeeded) {
        write(ProductAdminAuditAction.SIZE_CHART_TEMPLATE_DELETE, actorMemberId, AdminAuditLog.target(SIZE_CHART_TEMPLATE, templateId), succeeded);
    }

    public void productSizeChartReplaced(long actorMemberId, long productId, boolean succeeded) {
        write(ProductAdminAuditAction.PRODUCT_SIZE_CHART_REPLACE, actorMemberId, AdminAuditLog.target(PRODUCT, productId), succeeded);
    }

    public void mediaImageUploadIssued(long actorMemberId, java.util.UUID id, boolean succeeded) {
        write(ProductAdminAuditAction.MEDIA_IMAGE_UPLOAD_ISSUE, actorMemberId,
            id == null ? null : AdminAuditLog.target("media-upload", id.toString()), succeeded);
    }

    public void mediaImageUploadConfirmed(long actorMemberId, java.util.UUID id, boolean succeeded) {
        write(ProductAdminAuditAction.MEDIA_IMAGE_UPLOAD_CONFIRM, actorMemberId,
            id == null ? null : AdminAuditLog.target("media-upload", id.toString()), succeeded);
    }

    private static void write(ProductAdminAuditAction action, long actorMemberId, String target, boolean succeeded) {
        AdminAuditLog.write(action.name(), actorMemberId, target, succeeded);
    }
}
