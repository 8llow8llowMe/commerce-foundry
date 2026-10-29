package com.commercefoundry.domainlayer.onboarding.application.port.out;

import com.commercefoundry.domainlayer.onboarding.application.exception.OnboardingException;
import com.commercefoundry.domainlayer.onboarding.application.port.out.query.ShippingPolicyFactQueryResult;

/**
 * 온보딩 업무 완료 판정에 쓰는 delivery-service 의 배송비 정책 저장 사실 (#357). 다른 서비스 DB 를 직접 읽지 않고 허용된 internal 조회만 쓴다.
 * 원천(원격 자원)마다 포트를 따로 둔다 — 다른 업무의 사실(예: 상품 등록)은 별도 포트 · 오류 코드로 더한다.
 */
public interface ShippingPolicyFactQueryPort {

    /**
     * @param memberId access 토큰으로 확인한 운영자
     * @throws OnboardingException {@code DELIVERY_SERVICE_UNAVAILABLE} — 서킷 오픈 · 전송 실패 · 타임아웃 · 4xx · 5xx · 응답 계약 위반
     */
    ShippingPolicyFactQueryResult findShippingPolicyFact(long memberId);
}
