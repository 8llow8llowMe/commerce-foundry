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
    static final String PRODUCT_IMAGE_UPLOAD = "product-image-upload";
    static final String DISPLAY_SECTION = "display-section";
    static final String LOOK = "look";
    /** 재고 알림 정책 감사({@code StockAlertAuditProcessor})와 같은 대상 이름이다. */
    static final String SKU = "sku";
    static final String PRODUCT_DRAFT = "product-draft";
    /** 순서 변경은 여러 행을 한 번에 바꿔 고정 키를 대상으로 둔다 — 받은 아이디 목록은 싣지 않는다. */
    static final String ORDER = "order";

    /** @param productId 만든 상품 아이디 — 실패해 없으면 {@code null}({@code target=-}) */
    public void productCreated(long actorMemberId, Long productId, boolean succeeded) {
        write(ProductAdminAuditAction.PRODUCT_CREATE, actorMemberId, productId == null ? null : AdminAuditLog.target(PRODUCT, productId), succeeded);
    }

    /** @param token 발급한 업로드 키의 토큰 — 실패해 없으면 {@code null}({@code target=-}). 키 원문 · 서명 URL 은 싣지 않는다(토큰만) */
    public void productImageUploadIssued(long actorMemberId, java.util.UUID token, boolean succeeded) {
        write(ProductAdminAuditAction.PRODUCT_IMAGE_UPLOAD_ISSUE, actorMemberId,
            token == null ? null : AdminAuditLog.target(PRODUCT_IMAGE_UPLOAD, token.toString()), succeeded);
    }

    public void productCustomOrderReplaced(long actorMemberId, long productId, boolean succeeded) {
        write(ProductAdminAuditAction.PRODUCT_CUSTOM_ORDER_REPLACE, actorMemberId, AdminAuditLog.target(PRODUCT, productId), succeeded);
    }

    public void productOptionsReplaced(long actorMemberId, long productId, boolean succeeded) {
        write(ProductAdminAuditAction.PRODUCT_OPTIONS_REPLACE, actorMemberId, AdminAuditLog.target(PRODUCT, productId), succeeded);
    }

    public void productImagesReplaced(long actorMemberId, long productId, boolean succeeded) {
        write(ProductAdminAuditAction.PRODUCT_IMAGES_REPLACE, actorMemberId, AdminAuditLog.target(PRODUCT, productId), succeeded);
    }

    public void productPricingReplaced(long actorMemberId, long productId, boolean succeeded) {
        write(ProductAdminAuditAction.PRODUCT_PRICING_REPLACE, actorMemberId, AdminAuditLog.target(PRODUCT, productId), succeeded);
    }

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

    public void sizeChartDraftCreated(long actorMemberId, long productId, boolean succeeded) {
        write(ProductAdminAuditAction.SIZE_CHART_DRAFT_CREATE, actorMemberId, AdminAuditLog.target(PRODUCT, productId), succeeded);
    }

    public void sizeChartDraftRefreshed(long actorMemberId, long draftId, boolean succeeded) {
        write(ProductAdminAuditAction.SIZE_CHART_DRAFT_REFRESH, actorMemberId, AdminAuditLog.target("size-chart-draft", draftId), succeeded);
    }

    public void sizeChartDraftApplied(long actorMemberId, long draftId, boolean succeeded) {
        write(ProductAdminAuditAction.SIZE_CHART_DRAFT_APPLY, actorMemberId, AdminAuditLog.target("size-chart-draft", draftId), succeeded);
    }

    public void mediaImageUploadIssued(long actorMemberId, java.util.UUID id, boolean succeeded) {
        write(ProductAdminAuditAction.MEDIA_IMAGE_UPLOAD_ISSUE, actorMemberId,
            id == null ? null : AdminAuditLog.target("media-upload", id.toString()), succeeded);
    }

    public void mediaImageUploadConfirmed(long actorMemberId, java.util.UUID id, boolean succeeded) {
        write(ProductAdminAuditAction.MEDIA_IMAGE_UPLOAD_CONFIRM, actorMemberId,
            id == null ? null : AdminAuditLog.target("media-upload", id.toString()), succeeded);
    }

    /** @param sectionId 만든 섹션 아이디 — 실패해 없으면 {@code null}({@code target=-}) */
    public void displaySectionCreated(long actorMemberId, Long sectionId, boolean succeeded) {
        write(
            ProductAdminAuditAction.DISPLAY_SECTION_CREATE, actorMemberId,
            sectionId == null ? null : AdminAuditLog.target(DISPLAY_SECTION, sectionId), succeeded
        );
    }

    public void displaySectionUpdated(long actorMemberId, long sectionId, boolean succeeded) {
        write(ProductAdminAuditAction.DISPLAY_SECTION_UPDATE, actorMemberId, AdminAuditLog.target(DISPLAY_SECTION, sectionId), succeeded);
    }

    public void displaySectionDeleted(long actorMemberId, long sectionId, boolean succeeded) {
        write(ProductAdminAuditAction.DISPLAY_SECTION_DELETE, actorMemberId, AdminAuditLog.target(DISPLAY_SECTION, sectionId), succeeded);
    }

    /** 대상은 고정 {@code display-section:order} — 받은 섹션 아이디 목록은 싣지 않는다. */
    public void displaySectionsReordered(long actorMemberId, boolean succeeded) {
        write(ProductAdminAuditAction.DISPLAY_SECTION_REORDER, actorMemberId, AdminAuditLog.target(DISPLAY_SECTION, ORDER), succeeded);
    }

    /** @param lookId 만든 룩 아이디 — 실패해 없으면 {@code null}({@code target=-}) */
    public void lookCreated(long actorMemberId, Long lookId, boolean succeeded) {
        write(ProductAdminAuditAction.LOOK_CREATE, actorMemberId, lookId == null ? null : AdminAuditLog.target(LOOK, lookId), succeeded);
    }

    public void lookUpdated(long actorMemberId, long lookId, boolean succeeded) {
        write(ProductAdminAuditAction.LOOK_UPDATE, actorMemberId, AdminAuditLog.target(LOOK, lookId), succeeded);
    }

    public void lookDeleted(long actorMemberId, long lookId, boolean succeeded) {
        write(ProductAdminAuditAction.LOOK_DELETE, actorMemberId, AdminAuditLog.target(LOOK, lookId), succeeded);
    }

    /** 대상은 고정 {@code look:order} — 받은 룩 아이디 목록은 싣지 않는다. */
    public void looksReordered(long actorMemberId, boolean succeeded) {
        write(ProductAdminAuditAction.LOOK_REORDER, actorMemberId, AdminAuditLog.target(LOOK, ORDER), succeeded);
    }

    /** 재고 조정 (#163 PR-C′). 대상 {@code sku:<skuId>} — 수량 · 기대 수량 · 관리 여부 · 사유 · 메모는 싣지 않는다(정본은 조정 이력 표). */
    public void stockAdjusted(long actorMemberId, long skuId, boolean succeeded) {
        write(ProductAdminAuditAction.STOCK_ADJUST, actorMemberId, AdminAuditLog.target(SKU, skuId), succeeded);
    }

    /**
     * SKU 원가 이력 추가 (#163 PR-C′). 대상은 원가 행 아이디가 아니라 {@code sku:<skuId>} 다 — 실패하면 행 아이디가 없고, 원가 조회 · 이력 화면의 열쇠가 SKU 다.
     * 금액 · 통화 · 원가 유형 · 적용 시작 시각 · 사유는 싣지 않는다(정본은 원가 이력 표).
     */
    public void skuCostCreated(long actorMemberId, long skuId, boolean succeeded) {
        write(ProductAdminAuditAction.SKU_COST_CREATE, actorMemberId, AdminAuditLog.target(SKU, skuId), succeeded);
    }

    /** @param draftId 만든 초안 아이디 — 실패해 없으면 {@code null}({@code target=-}). 입력 사실 · 문구 · 이미지는 싣지 않는다 */
    public void productDraftCreated(long actorMemberId, Long draftId, boolean succeeded) {
        write(ProductAdminAuditAction.PRODUCT_DRAFT_CREATE, actorMemberId, draftId == null ? null : AdminAuditLog.target(PRODUCT_DRAFT, draftId), succeeded);
    }

    public void productDraftRejected(long actorMemberId, long draftId, boolean succeeded) {
        write(ProductAdminAuditAction.PRODUCT_DRAFT_REJECT, actorMemberId, AdminAuditLog.target(PRODUCT_DRAFT, draftId), succeeded);
    }

    private static void write(ProductAdminAuditAction action, long actorMemberId, String target, boolean succeeded) {
        AdminAuditLog.write(action.name(), actorMemberId, target, succeeded);
    }
}
