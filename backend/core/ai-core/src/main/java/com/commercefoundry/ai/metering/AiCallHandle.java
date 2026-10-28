package com.commercefoundry.ai.metering;

/**
 * {@link AiCallInterceptor#before} 가 돌려주고 {@link AiCallInterceptor#after} 가 그대로 받는 불투명 핸들. 클라이언트는 열어 보지 않는다 —
 * 예약 ID 처럼 인터셉터가 필요한 것을 구현 타입에 담는다.
 */
public interface AiCallHandle {

}
