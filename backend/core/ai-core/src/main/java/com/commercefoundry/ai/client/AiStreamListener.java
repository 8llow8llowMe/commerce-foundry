package com.commercefoundry.ai.client;

/**
 * 스트리밍 대화({@link AiClient#converseStreaming})의 텍스트 조각을 받는다. 호출한 스레드에서 차례로 불린다 — 이 콜백이 오래 걸리면 스트림 읽기도 멈춘다
 * (그동안 서버 쪽 버퍼가 차면 연결이 끊길 수 있다). 무거운 일은 큐로 넘긴다.
 *
 * <p>조각은 모델 출력 원문이다(가리지 않는다 — 모델은 가린 입력만 봤다). 화면에 그릴 때 이스케이프하고, 로그에 남기지 않는다.
 * 도구 호출 인자 · thinking 은 조각으로 오지 않는다 — 최종 결과에만 있다.
 */
@FunctionalInterface
public interface AiStreamListener {

    /**
     * 텍스트 조각 하나.
     *
     * @return {@code false} 면 그만 받는다 — 스트림을 닫고 {@code cancelled} 결과를 돌려준다
     */
    boolean onTextDelta(String delta);

    /**
     * 요청을 보내기 직전에 한 번 불린다. 다른 스레드에서 취소하려면 핸들을 잡아 둔다(고객이 연결을 끊었을 때 등).
     * 동시 상한 · 한도에 막혀 보내지 않으면 불리지 않는다. 여기서 던지면 보내지 않고(원장 {@code NOT_SENT}, 원인 {@code null}) 예외가 그대로 나간다.
     * 여기서 바로 {@link AiStreamHandle#cancel()} 하면 보내지 않고 {@code CANCELLED} 결과다({@code NOT_SENT}).
     * {@link #onTextDelta} 가 던진 예외도 그대로 나가고 원장 원인은 {@code null} 이다(상태는 받은 사용량이 있으면 {@code UNUSABLE}, 없으면 {@code TIMEOUT}).
     */
    default void onStart(AiStreamHandle handle) {
    }
}
