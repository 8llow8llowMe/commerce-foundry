package com.commercefoundry.domainlayer.job.domain.model;

import java.time.Instant;

/**
 * 핸들러가 받는 실행 한 번(attempt)의 문맥. 워커가 만들고, 실행이 끝나면 버린다.
 */
public interface JobExecution {

    long jobId();

    String type();

    /** 이번 시도 번호 (1 부터). 다시 실행되면 늘어난다 — 결과 파일 키처럼 시도마다 달라야 하는 값에 쓴다. */
    int attempt();

    /** 등록할 때 받은 JSON. 개인정보 원문이 없다 — 아이디로 원본을 읽는다. */
    String payloadJson();

    /** 이번 시도를 가져간 시각. */
    Instant startedAt();

    /**
     * 진행률을 남기고 중단 신호를 확인한다. 진행률은 heartbeat 가 lease 연장과 함께 DB 에 쓴다 — 부를 때마다 쓰지 않으므로 자주 불러도 된다.
     *
     * @param percent 0 ~ 100
     * @param message 화면에 보일 짧은 문구 (200자 이하, 개인정보 없이). 없으면 null
     * @throws JobCancelledException 취소 요청을 받았거나, lease 를 잃었거나, 실행 시간 상한을 넘었다. 핸들러는 잡지 말고 그대로 내보낸다
     */
    void checkpoint(int percent, String message);

    /** 취소 요청 · lease 상실 · 실행 시간 초과 중 하나가 있었는가. 예외 없이 확인할 때 쓴다. */
    boolean isCancelRequested();
}
