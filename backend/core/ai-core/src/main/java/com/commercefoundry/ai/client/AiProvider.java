package com.commercefoundry.ai.client;

import com.commercefoundry.ai.masking.PiiMasker;
import com.commercefoundry.ai.metering.AiCallInterceptor;
import com.commercefoundry.ai.properties.AiProperties;

/**
 * Provider 별 클라이언트 생성 경계 (#468 PR-A). SDK 타입은 이 계약에 나타나지 않는다.
 * 내부 SPI 로 둔다 — 서비스는 {@link AiClients} 와 {@link AiClient} 만 사용한다.
 *
 * <p>호출 전 설정 · 계량 개수 검증과 disabled 처리는 {@link AiClients} 책임이다. 구현은 주어진 마스커와 계량 인터셉터를 모든 호출 경로에 적용하며,
 * SDK 의 환경변수 키 탐색 · 본문 로그를 사용하지 않는다. 생성 중 외부 API 를 호출하거나 다른 Provider 로 폴백하지 않는다.
 */
@FunctionalInterface
interface AiProvider {

    /** 설정은 공통 가드를 통과했다. interceptor 는 명시적으로 미계량을 허용한 경우에만 null 이다. */
    AiClient create(AiProperties properties, PiiMasker masker, AiCallInterceptor interceptor);
}
