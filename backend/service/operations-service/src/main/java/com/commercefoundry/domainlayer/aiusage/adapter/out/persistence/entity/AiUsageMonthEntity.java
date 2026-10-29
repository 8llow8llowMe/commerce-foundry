package com.commercefoundry.domainlayer.aiusage.adapter.out.persistence.entity;

import com.commercefoundry.persistence.entity.BaseEntity;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * AI 사용 월 카운터 (#215 PR-B) — 월 한도의 판정 대상. 행은 그 달 첫 예약이 만들고 지우지 않는다.
 *
 * <p>모든 쓰기가 조건부 UPDATE 한 문장이다 ({@code AiUsageMonthRepository}) — 예약은 "누계 + 예상치 ≤ 한도" 일 때만, 경고 · 초과 표시는 "아직 비었을 때만".
 * 같은 행을 여러 호출이 동시에 바꾸면 PostgreSQL 행 잠금이 줄 세우고 조건을 다시 평가한다. 쓰기마다 버전도 올린다 — 이 행을 읽어 merge 하는 쓰기가 생겨도 옛 누계로 덮지 못하게.
 *
 * <p>{@code reserved_micros} = 확정 비용 + 진행 중 예약의 예상치, {@code settled_micros} = 확정 비용. 그래서 예약 누계는 확정 누계보다 작을 수 없다.
 */
@Entity
@Table(
    name = "ai_usage_month",
    comment = "AI 사용 월 카운터 (월 한도 예약 · 정산 누계)",
    uniqueConstraints = @UniqueConstraint(name = "uk_ai_usage_month_period", columnNames = "period"),
    check = {
        @CheckConstraint(
            name = "ck_ai_usage_month_non_negative",
            constraint = "reserved_micros >= 0 and settled_micros >= 0 and call_count >= 0 and limit_micros_snapshot >= 0"
        ),
        @CheckConstraint(name = "ck_ai_usage_month_reserved_covers_settled", constraint = "reserved_micros >= settled_micros"),
        // YYYY-MM — 정규식은 H2 · PostgreSQL 이 달라 길이 · 구분자 · 숫자만으로 본다 (coding-conventions.md §9-4)
        @CheckConstraint(
            name = "ck_ai_usage_month_period_format",
            constraint = "length(period) = 7 and substr(period, 5, 1) = '-' and ltrim(replace(period, '-', ''), '0123456789') = ''"
        )
    }
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class AiUsageMonthEntity extends BaseEntity {

    @Id
    @Column(name = "id", comment = "월 카운터 아이디 (Snowflake)")
    private Long id;

    @Column(name = "period", nullable = false, length = 7, comment = "월 YYYY-MM (operations.ai.budget.zone 기준)")
    private String period;

    @Column(name = "reserved_micros", nullable = false, comment = "예약 누계 (micro-USD, cost_currency) — 확정 비용 + 진행 중 예약의 예상치. 한도 판정 대상")
    private long reservedMicros;

    @Column(name = "settled_micros", nullable = false, comment = "확정 비용 누계 (micro-USD, cost_currency)")
    private long settledMicros;

    @Column(name = "call_count", nullable = false, comment = "닫힌 호출 수 (보내지 않은 NOT_SENT 제외)")
    private long callCount;

    @Column(name = "limit_micros_snapshot", nullable = false, comment = "마지막 예약 때의 월 한도 (micro-USD, cost_currency) — 설정을 바꾸면 다음 예약부터 바뀐다")
    private long limitMicrosSnapshot;

    @Column(name = "cost_currency", nullable = false, length = 3, comment = "금액 칸의 통화 — 언제나 USD")
    private String costCurrency;

    @Column(name = "warned_at", comment = "경고 임계(한도 × warn-ratio)를 처음 넘은 시각. 기간당 한 번. 아직이면 null")
    private Instant warnedAt;

    @Column(name = "exceeded_at", comment = "한도를 처음 넘은 시각(첫 예약 거절 또는 정산 뒤 누계 ≥ 한도). 기간당 한 번. 아직이면 null")
    private Instant exceededAt;

    @Version
    @Column(name = "version", nullable = false, comment = "낙관적 락 버전 — 조건부 UPDATE 마다 올린다")
    private Long version;
}
