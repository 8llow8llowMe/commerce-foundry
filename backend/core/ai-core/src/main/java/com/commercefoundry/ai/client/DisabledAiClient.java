package com.commercefoundry.ai.client;

/** {@code ai.enabled=false} 일 때의 클라이언트 — 언제나 {@code AiException(DISABLED)} 다. 서비스 어댑터는 빈 유무를 분기하지 않는다. */
final class DisabledAiClient implements AiClient {

    @Override
    public AiResult complete(AiRequest request) {
        throw new AiException(AiException.Reason.DISABLED, request.feature());
    }
}
