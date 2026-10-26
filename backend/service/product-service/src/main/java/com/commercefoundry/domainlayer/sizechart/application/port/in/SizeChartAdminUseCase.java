package com.commercefoundry.domainlayer.sizechart.application.port.in;

import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.item.SizeChartTemplateSummaryAdminItem;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.response.ProductSizeChartAdminResponse;
import com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.response.SizeChartTemplateAdminResponse;
import com.commercefoundry.domainlayer.sizechart.application.command.ProductSizeChartUpsertCommand;
import com.commercefoundry.domainlayer.sizechart.application.command.SizeChartTemplateUpsertCommand;
import com.commercefoundry.persistence.dto.PageResponse;

/**
 * 어드민 사이즈표 템플릿 · 상품 사이즈표. 쓰기는 템플릿 행 잠금 안에서 판정한다. 잠금이 시간 상한 안에 안 풀리면 {@code PRODUCT_072}(409)다.
 * 표가 없으면 상품 사이즈표 조회는 null 이다 (200). 작성자 칸 · Idempotency-Key 는 두지 않는다.
 */
public interface SizeChartAdminUseCase {

    /** 최신순(아이디 내림차순). 마지막 페이지를 넘기면 빈 contents 다. */
    PageResponse<SizeChartTemplateSummaryAdminItem> getTemplates(int page, int size);

    /** 없으면 {@code PRODUCT_059}(404)다. */
    SizeChartTemplateAdminResponse getTemplate(long templateId);

    /** 항목 이름 · 순서 중복은 {@code PRODUCT_060} · {@code PRODUCT_061}(400)이다. */
    SizeChartTemplateAdminResponse createTemplate(SizeChartTemplateUpsertCommand command);

    /**
     * 전체 교체. 없으면 {@code PRODUCT_059}(404). 상품 사이즈표가 쓰면 단위 변경 · 항목 추가 · 삭제는 {@code PRODUCT_062}(409)이고
     * 기존 항목 아이디로 이름 · 설명 · 순서만 바꾼다. 아무도 안 쓰면 항목을 새 아이디로 다시 만든다.
     */
    SizeChartTemplateAdminResponse updateTemplate(long templateId, SizeChartTemplateUpsertCommand command);

    /** 없으면 {@code PRODUCT_059}(404), 쓰는 중이면 {@code PRODUCT_063}(409)다. 항목까지 지운다. */
    void deleteTemplate(long templateId);

    /**
     * 숨긴 상품도 본다. 상품이 없으면 {@code PRODUCT_064}(404) — 상품 상세의 PRODUCT_001 을 쓰지 않는다.
     * 상품은 있고 표가 없으면 null 이다.
     */
    ProductSizeChartAdminResponse getProductSizeChart(long productId);

    /**
     * 없으면 만들고 있으면 행 · 칸을 통째로 바꾼다. 상품이 없으면 {@code PRODUCT_064}(404), 본문 템플릿이 없으면 {@code PRODUCT_065}(400),
     * 항목 집합이 어긋나면 {@code PRODUCT_066}~{@code PRODUCT_068}(400), 사이즈 이름 · 순서 중복은 {@code PRODUCT_069} · {@code PRODUCT_070}(400)이다.
     * 응답 값은 저장 단위 그대로다.
     */
    ProductSizeChartAdminResponse replaceProductSizeChart(long productId, ProductSizeChartUpsertCommand command);
}
