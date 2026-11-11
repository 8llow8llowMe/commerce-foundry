package com.commercefoundry.domainlayer.productdraft.application.port.in;

import com.commercefoundry.domainlayer.productdraft.adapter.in.admin.dto.response.ProductDraftAdminResponse;
import com.commercefoundry.domainlayer.productdraft.application.command.ProductDraftCreateCommand;

/**
 * 어드민 AI 상품 초안 (#359). 초안은 상품을 바꾸거나 공개하지 않는다 — 검토한 값만 적용 단계(후속 PR)에서 상품 등록 검증을 거친다.
 * 이 단계는 초안 저장 · 조회 · 거절이다. 생성 작업 연결은 후속 PR 이다 — 그때까지 새 초안은 생성 대기({@code REQUESTED})에 머문다.
 */
public interface ProductDraftAdminUseCase {

    /** 이미지 판정 오류는 {@code PRODUCT_DRAFT_030 · 031 · 033 · 034 · 035 · 036}(400) · {@code 032} · {@code 501}(503)이다. 이미지는 관리 키 · ETag 를 고정해 저장한다. */
    ProductDraftAdminResponse create(ProductDraftCreateCommand command);

    /** 없으면 {@code PRODUCT_DRAFT_001}(404)이다. 보관 기간이 지난 진행 중 초안은 만료로 보인다. */
    ProductDraftAdminResponse getDraft(long draftId);

    /** 이미 거절했으면 그대로 200 이다. 적용한 초안은 {@code PRODUCT_DRAFT_020}, 만료는 {@code 022}, 동시 변경은 {@code 025}(409)다. */
    ProductDraftAdminResponse reject(long draftId, long memberId);
}
