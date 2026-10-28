package com.commercefoundry.ai.metering;

import com.commercefoundry.ai.client.AiException;
import com.commercefoundry.ai.client.AiUsage;

/**
 * 호출 한 번이 어떻게 끝났는지. {@link AiCallInterceptor#after} 가 받는다. 원문 · 개인정보는 없다.
 *
 * @param status     과금 관점의 결과 구분
 * @param usage      API 가 알려 준 사용량. {@link Status#COMPLETED} · {@link Status#UNUSABLE} 에만 있고 나머지는 {@code null}
 * @param model      답한 모델 ID. 응답이 없으면 요청한 모델이다
 * @param reason     실패 원인. {@link Status#COMPLETED} 면 {@code null}
 * @param statusCode API 오류 응답의 상태 코드. 오류 응답이 아니면 {@code null}
 * @param messageId  API 의 메시지 ID. 응답이 없으면 {@code null}
 * @param elapsedMs  자리를 잡은 뒤 응답 · 실패까지 걸린 시간(SDK 재시도 포함). {@link Status#NOT_SENT} 면 0
 */
public record AiCallOutcome(Status status, AiUsage usage, String model, AiException.Reason reason, Integer statusCode, String messageId, long elapsedMs) {

    /** 과금 관점의 결과. 원장은 이 구분으로 실측 · 0 · 예상치 중 무엇을 남길지 고른다. */
    public enum Status {

        /** 응답을 받아 결과로 썼다. {@code usage} 는 실측이다. */
        COMPLETED,
        /** 응답을 받았지만 쓰지 못했다(거절 · 잘림 · 잘못된 형식 · 검증 실패 · 사용량을 읽은 뒤 해석 실패). 과금됐다 — {@code usage} 는 실측이다. */
        UNUSABLE,
        /**
         * API 가 오류 응답(4xx · 429 · 5xx · 529)을 줬다. {@code usage} 는 없고 마지막 시도는 과금되지 않는다.
         * SDK 재시도 중 앞선 시도가 타임아웃이었다면 그 시도분(과금됐을 수 있다)은 빠진다 — {@code max-retries} 를 낮게 둔다.
         */
        FAILED,
        /**
         * 쓸 수 있는 응답이 없다(연결 오류 · 타임아웃 · 사용량조차 읽지 못한 응답). 생성 · 과금됐는지 모른다 — {@code usage} 가 없으니 원장은 예상치를 비용으로 남긴다.
         */
        TIMEOUT,
        /** 동시 상한 자리를 얻지 못해({@code BUSY}) 보내지 않았다. 비용 0 — 원장은 예약을 풀어 준다. */
        NOT_SENT
    }
}
