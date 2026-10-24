package com.commercefoundry.ai.client;

/**
 * Claude API 호출의 공통 입구. 구현은 언제나 개인정보를 가린 뒤 보낸다 — 가리지 않고 보내는 경로는 없다.
 *
 * <p>서비스는 이 타입을 {@code adapter/out/client} 의 어댑터에서만 쓰고, {@link AiException#reason()} 을 자기 컨텍스트의 오류로 번역한다
 * ({@code ObjectStorageClient} 와 같은 자리, {@code bootstrap-conventions.md} AI). 프롬프트 문구 · 결과 해석은 서비스 몫이다.
 */
public interface AiClient {

    /**
     * 요청 하나를 보내고 텍스트 결과를 받는다.
     *
     * @throws AiException 꺼져 있거나 · 동시 상한 대기를 넘었거나 · API 가 실패 · 거절했거나 · 마스킹에 실패했을 때.
     *                     {@link AiException.Reason#retryable()} 로 다시 시도할지 가린다
     */
    AiResult complete(AiRequest request);
}
