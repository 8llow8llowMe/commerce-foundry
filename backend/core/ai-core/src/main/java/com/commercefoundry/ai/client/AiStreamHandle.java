package com.commercefoundry.ai.client;

/** 진행 중인 스트리밍 호출을 다른 스레드에서 멈추는 핸들 ({@link AiStreamListener#onStart}). */
public interface AiStreamHandle {

    /**
     * 스트림을 닫는다 — 읽고 있던 호출은 {@code cancelled} 결과로 돌아온다. 여러 번 불러도 된다. 끝난 호출에는 아무 일도 없다.
     *
     * <p>한계: SDK 가 HTTP 호출 취소(OkHttp {@code Call.cancel})를 드러내지 않아, 바이트를 기다리며 막혀 있던 읽기는 다음 이벤트(델타 · {@code ping}) ·
     * 서버 닫힘 · {@code ai.read-timeout} 중 먼저 오는 때에 풀린다. 그 사이 동시 상한 자리와 연결을 잡고 있다. 응답 헤더를 받기 전의 취소는 헤더가 오는 즉시 닫는다.
     */
    void cancel();

    boolean cancelled();
}
