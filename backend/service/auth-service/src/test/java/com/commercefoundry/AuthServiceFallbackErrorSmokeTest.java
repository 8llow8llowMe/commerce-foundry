package com.commercefoundry;

import com.commercefoundry.common.support.FallbackErrorSmokeTestSupport;

/**
 * 폴백 오류 봉투 스모크 — 도메인 · 보안 advice 가 받지 않는 404 · 405 · 415 · 400 · 500 이 이 서비스에서도 {@code COMMON_00x} 봉투로 나가는지 본다
 * ({@code api-design-guide.md} §2-1 공통 오류 코드). 애노테이션은 {@link AuthServiceApplicationTests} 와 같아 컨텍스트를 같이 쓴다.
 */
class AuthServiceFallbackErrorSmokeTest extends FallbackErrorSmokeTestSupport {
}
