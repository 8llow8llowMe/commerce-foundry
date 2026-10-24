package com.commercefoundry.ai.client;

/**
 * Claude API 호출의 공통 입구. 구현은 언제나 개인정보를 가린 뒤 보낸다 — 가리지 않고 보내는 경로는 없다.
 *
 * <p>서비스는 이 타입을 {@code adapter/out/client} 의 어댑터에서만 쓰고, {@link AiException#reason()} 을 자기 컨텍스트의 오류로 번역한다
 * ({@code ObjectStorageClient} 와 같은 자리, {@code bootstrap-conventions.md} AI). 프롬프트 문구는 {@code PromptTemplates} 의 리소스, 결과 해석은 서비스 몫이다.
 */
public interface AiClient {

    /**
     * 요청 하나를 보내고 텍스트 결과를 받는다.
     *
     * @throws AiException 꺼져 있거나 · 입력이 너무 길거나 · 동시 상한 대기를 넘었거나 · API 가 실패 · 거절했거나 · 마스킹에 실패했을 때.
     *                     {@link AiException.Reason#retryable()} 로 다시 시도할지 가린다
     */
    AiResult complete(AiRequest request);

    /**
     * 요청 하나를 보내고 {@code type} 모양의 구조화 결과를 받는다 — API 의 JSON 출력(스키마는 {@code type} 에서 만든다)으로 받아 파싱한 뒤
     * {@code validator} 로 업무 규칙을 본다. 응답은 신뢰할 수 없는 출력이다 — 스키마가 맞아도 값의 뜻(범위 · 합계 · 허용 값)은 validator 가 본다.
     *
     * @param type      결과 타입. 필드가 단순한 record 를 권한다 — API 가 받지 않는 스키마(재귀 등)면 SDK 의 예외(호스트 결함)다
     * @param validator 값을 보고 문제 설명 목록을 돌려준다. 비어 있지 않으면 {@code INVALID_RESPONSE} 다. 설명은 로그에 남기지 않는다(개수만)
     * @throws AiException {@link #complete(AiRequest)} 와 같고, 잘렸거나 · 파싱하지 못했거나 · 검증에 실패하면 {@code INVALID_RESPONSE}(재시도 안 함)
     */
    <T> AiStructuredResult<T> complete(AiRequest request, Class<T> type, AiOutputValidator<T> validator);

    /** 업무 검증 없이 스키마 · 파싱만 본다. */
    default <T> AiStructuredResult<T> complete(AiRequest request, Class<T> type) {
        return complete(request, type, AiOutputValidator.none());
    }
}
