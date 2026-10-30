package com.commercefoundry.domainlayer.onboarding.domain.model;

/**
 * 실제 업무 완료를 판정하는 온보딩 업무 (#357). 판정은 담당 서비스가 확인한 사실로만 한다 — 안내 투어를 본 기록과 섞지 않는다.
 * 응답 순서는 선언 순서다. 화면이 분기에만 쓰는 제어 코드라 metadata 가 아니라 이름 그대로 내린다 ({@code coding-conventions.md} §11 예외).
 */
public enum OnboardingTaskKey {

    /** 국내 배송비 정책 저장 — 사실은 delivery-service 가 낸다(행위자 이력이 있는 국내 정책만, 해외 정책 교체는 보지 않는다). */
    SHIPPING_POLICY_SAVED,
    /** 상품 1건 등록 — 스토어 준비와 운영자 등록 사실은 product-service 가 따로 낸다. */
    PRODUCT_REGISTERED
}
