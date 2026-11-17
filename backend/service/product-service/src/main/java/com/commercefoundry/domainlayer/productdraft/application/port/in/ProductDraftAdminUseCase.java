package com.commercefoundry.domainlayer.productdraft.application.port.in;

import com.commercefoundry.domainlayer.productdraft.adapter.in.admin.dto.response.ProductDraftAdminResponse;
import com.commercefoundry.domainlayer.productdraft.application.command.ProductDraftCreateCommand;

/**
 * 어드민 AI 상품 초안 (#359). 초안은 상품을 바꾸거나 공개하지 않는다 — 검토한 값만 적용 단계(후속 PR)에서 상품 등록 검증을 거친다.
 * 이 단계는 초안 저장 · 조회 · 거절과 생성 시작(operations 작업 등록, #359 PR-B1)이다. 생성 결과 반영 · 재생성은 후속 PR 이다.
 */
public interface ProductDraftAdminUseCase {

    /**
     * 이미지 판정 오류는 {@code PRODUCT_DRAFT_030 · 031 · 033 · 034 · 035 · 036 · 037}(400) · {@code 032} · {@code 501}(503)이다. 이미지는 관리 키 · ETag 를 고정해 저장한다.
     * 생성 플래그가 열려 있으면 저장 뒤 1회차 생성을 시작한다(#359 D14) — 시작이 실패해도 저장은 성공이고 응답은 생성 대기 초안이다.
     */
    ProductDraftAdminResponse create(ProductDraftCreateCommand command);

    /**
     * 생성 시작 · 재시도. 시작 전이면 회차를 시작하고 등록한다. 시작했지만 작업이 없으면 같은 값으로 다시 등록한다. 이미 생성 중이면 그대로 돌려준다.
     * 닫힘 {@code PRODUCT_DRAFT_050}(400) · 없음 {@code 001}(404) · 만료 {@code 022} · 동시 변경 {@code 025} · 상태 {@code 051} · 원본 미고정 {@code 052}(409) ·
     * 등록 여부 모름 {@code 502}(503) · 응답 계약 위반 {@code 504}(502)이다.
     */
    ProductDraftAdminResponse generate(long draftId, long memberId);

    /** 없으면 {@code PRODUCT_DRAFT_001}(404)이다. 보관 기간이 지난 진행 중 초안은 만료로 보인다. */
    ProductDraftAdminResponse getDraft(long draftId);

    /** 이미 거절했으면 그대로 200 이다. 적용한 초안은 {@code PRODUCT_DRAFT_020}, 만료는 {@code 022}, 동시 변경은 {@code 025}(409)다. */
    ProductDraftAdminResponse reject(long draftId, long memberId);
}
