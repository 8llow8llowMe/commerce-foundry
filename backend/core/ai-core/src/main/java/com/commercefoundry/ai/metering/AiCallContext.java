package com.commercefoundry.ai.metering;

/**
 * 보내기 전에 인터셉터가 받는 호출 정보. 원문 · 개인정보 · 이미지 바이트는 없다 — 길이 · 개수만이다.
 *
 * @param callId         클라이언트가 호출마다 만든 ID(UUID). 원장 행 키로 쓸 수 있다
 * @param feature        기능 이름({@code AiRequest.feature})
 * @param promptVersion  템플릿 버전. 템플릿 없이 만든 요청이면 {@code null}
 * @param callerRef      호출한 작업 · 업무의 식별자({@code AiRequest.callerRef}). 없으면 {@code null}
 * @param requestedModel 설정에서 고른 모델 ID — 거절 대체가 일어나면 답한 모델은 {@link AiCallOutcome#model()} 이다
 * @param maxTokens      최대 출력 토큰 — 예상 비용의 출력 몫
 * @param inputChars     <b>마스킹 뒤</b> 시스템 + 사용자 프롬프트 글자 수 — 예상 비용의 입력 몫
 * @param imageCount     이미지 장수
 * @param structured     구조화 출력 호출이면 {@code true}
 */
public record AiCallContext(String callId, String feature, Integer promptVersion, String callerRef, String requestedModel, int maxTokens, long inputChars,
                            int imageCount, boolean structured) {

}
