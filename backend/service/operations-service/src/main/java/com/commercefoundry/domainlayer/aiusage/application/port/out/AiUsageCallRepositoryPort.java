package com.commercefoundry.domainlayer.aiusage.application.port.out;

import com.commercefoundry.domainlayer.aiusage.domain.model.AiUsageCall;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 원장 호출 행. 호출자의 트랜잭션 안에서 돈다. */
public interface AiUsageCallRepositoryPort {

    /** 예약한 호출(PENDING)을 새 행으로 넣는다 — 아이디 충돌은 PK 위반으로 실패한다. */
    void insert(AiUsageCall call);

    Optional<AiUsageCall> findById(long callId);

    /**
     * 닫힌 호출({@link AiUsageCall#settle} · {@link AiUsageCall#abandon} 결과)을 "아직 PENDING 일 때만" 쓴다.
     *
     * @return 이번에 닫았으면 {@code true}. 다른 쪽(중복 정산 · 회수)이 먼저 닫았으면 {@code false} 이고 아무것도 바꾸지 않았다
     */
    boolean closeIfPending(AiUsageCall closed);

    /** 호출 시각이 {@code calledBefore} 보다 이른 PENDING 을 오래된 순으로 최대 {@code limit} 건. */
    List<AiUsageCall> findPendingCalledBefore(Instant calledBefore, int limit);

    /**
     * 호출 시각이 {@code cutoff} 보다 이른 닫힌 호출 행을 최대 {@code limit} 건 지운다. PENDING 은 회수가 닫을 때까지 남긴다.
     *
     * @return 지운 건수
     */
    int deleteClosedCalledBefore(Instant cutoff, int limit);
}
