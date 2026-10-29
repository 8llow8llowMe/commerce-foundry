package com.commercefoundry.domainlayer.aiusage.application.exception;

import com.commercefoundry.domainlayer.aiusage.application.info.AiBudgetAlert;
import java.util.Objects;
import lombok.Getter;

/**
 * 월 한도를 넘어 예약하지 않았다 ({@link AiUsageErrorCode#BUDGET_EXCEEDED}). 던질 때는 거절 표시({@code exceeded_at})가 이미 커밋돼 있다 —
 * {@link AiBudgetAlert#exceedCrossed()} 가 참이면 이 달 첫 거절이라 받은 쪽이 한 번 알린다.
 *
 * <p>{@link #isSingleCallOverLimit()} 가 참이면 예상치 하나가 월 한도보다 크다 — 누계와 관계없이 영원히 들어가지 않는 호출(설정 실수: 최대 출력 토큰 · 한도)이라
 * 이 달의 초과 표시를 세우지 않았다. 받은 쪽은 한도 알림이 아니라 설정 경고(WARN)로 남긴다.
 */
@Getter
public class AiBudgetExceededException extends AiUsageException {

    private final transient AiBudgetAlert alert;
    private final long estimateCostMicros;
    private final boolean singleCallOverLimit;

    public AiBudgetExceededException(AiBudgetAlert alert, long estimateCostMicros, boolean singleCallOverLimit) {
        super(AiUsageErrorCode.BUDGET_EXCEEDED);
        this.alert = Objects.requireNonNull(alert, "alert");
        this.estimateCostMicros = estimateCostMicros;
        this.singleCallOverLimit = singleCallOverLimit;
    }
}
