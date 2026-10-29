package com.commercefoundry.domainlayer.aiusage.adapter.out.persistence.entity;

import com.commercefoundry.domainlayer.aiusage.domain.model.AiPricedBy;
import com.commercefoundry.domainlayer.aiusage.domain.model.AiUsageStatus;
import com.commercefoundry.persistence.entity.BaseEntity;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

/**
 * AI 호출 한 건의 사용량 · 비용 (#215 PR-B). 예약 때 PENDING 으로 넣고, 결과 · 회수는 "아직 PENDING 일 때만" 조건부 UPDATE 로 한 번만 닫는다
 * ({@code AiUsageCallRepository}). 닫힌 행은 다시 바뀌지 않아 {@code @Version} 을 두지 않는다 — Snowflake 아이디를 직접 넣으므로 {@link Persistable} 로
 * 새 행을 persist 한다 ({@code coding-conventions.md} §9-1).
 *
 * <p>원문 · 개인정보는 없다 — 기능 · 글자 수 · 이미지 장수 · 토큰 수 · 작업 식별자만이다. 일별 · 기능별 집계는 이 행을 조회로 묶는다. 보존 기간이 지나면 지운다.
 *
 * <p>인덱스 — 보존 정리({@code called_at, id}), 멈춘 예약 회수({@code status, called_at}). 기능별 · 작업별 조회 인덱스는 조회 API 이슈에서 쿼리와 함께 더한다.
 */
@Entity
@Table(
    name = "ai_usage_call",
    comment = "AI 호출 사용량 · 비용 원장 (호출 단위)",
    indexes = {
        @Index(name = "idx_ai_usage_call_called_at_id", columnList = "called_at, id"),
        @Index(name = "idx_ai_usage_call_status_called_at", columnList = "status, called_at")
    },
    check = {
        @CheckConstraint(
            name = "ck_ai_usage_call_non_negative",
            constraint = "image_count >= 0 and input_chars >= 0 and estimate_cost_micros >= 0 and input_tokens >= 0 and output_tokens >= 0"
                + " and cache_creation_input_tokens >= 0 and cache_read_input_tokens >= 0 and (cost_micros is null or cost_micros >= 0)"
        ),
        @CheckConstraint(
            name = "ck_ai_usage_call_settled",
            constraint = "(status = 'PENDING' and cost_micros is null and settled_at is null)"
                + " or (status <> 'PENDING' and cost_micros is not null and settled_at is not null)"
        ),
        // YYYY-MM — 정규식은 H2 · PostgreSQL 이 달라 길이 · 구분자 · 숫자만으로 본다 (coding-conventions.md §9-4)
        @CheckConstraint(
            name = "ck_ai_usage_call_period_format",
            constraint = "length(period) = 7 and substr(period, 5, 1) = '-' and ltrim(replace(period, '-', ''), '0123456789') = ''"
        )
    }
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class AiUsageCallEntity extends BaseEntity implements Persistable<Long> {

    @Id
    @Column(name = "id", comment = "AI 호출 아이디 (Snowflake)")
    private Long id;

    @Column(name = "period", nullable = false, length = 7, comment = "비용을 매기는 월 YYYY-MM (예약 시각, operations.ai.budget.zone 기준). ai_usage_month.period 와 같다")
    private String period;

    @Column(name = "feature", nullable = false, length = 63, comment = "기능 이름 (ai-core 요청의 feature)")
    private String feature;

    @Column(name = "prompt_version", comment = "프롬프트 템플릿 버전. 템플릿 없이 만든 요청이면 null")
    private Integer promptVersion;

    @Column(name = "caller_ref", length = 100, comment = "호출한 작업 식별자 (예 job:123, 내부 PK 만). 없으면 null")
    private String callerRef;

    @Column(name = "requested_model", nullable = false, length = 64, comment = "요청한 모델 ID — 예상 비용의 단가")
    private String requestedModel;

    @Column(name = "model", length = 64, comment = "답한 모델 ID. 응답이 없으면 요청한 모델, PENDING · ABANDONED 면 null")
    private String model;

    @Column(name = "structured", nullable = false, comment = "구조화 출력 호출이다")
    private boolean structured;

    @Column(name = "image_count", nullable = false, comment = "이미지 장수")
    private int imageCount;

    @Column(name = "input_chars", nullable = false, comment = "마스킹 뒤 입력 글자 수 — 예상 비용의 입력 몫")
    private int inputChars;

    @Column(name = "estimate_cost_micros", nullable = false, comment = "예약한 예상 비용 (micro-USD, cost_currency) — TIMEOUT · ABANDONED 는 이 값이 확정 비용이다")
    private long estimateCostMicros;

    @Column(name = "input_tokens", nullable = false, comment = "실측 입력 토큰 (캐시 제외). 응답이 없으면 0")
    private long inputTokens;

    @Column(name = "output_tokens", nullable = false, comment = "실측 출력 토큰. 응답이 없으면 0")
    private long outputTokens;

    @Column(name = "cache_creation_input_tokens", nullable = false, comment = "실측 캐시 쓰기 입력 토큰. 응답이 없으면 0")
    private long cacheCreationInputTokens;

    @Column(name = "cache_read_input_tokens", nullable = false, comment = "실측 캐시 읽기 입력 토큰. 응답이 없으면 0")
    private long cacheReadInputTokens;

    @Column(name = "cost_micros", comment = "확정 비용 (micro-USD, cost_currency, 합계 올림). PENDING 이면 null")
    private Long costMicros;

    @Column(name = "cost_currency", nullable = false, length = 3, comment = "비용 통화 — 언제나 USD")
    private String costCurrency;

    @Column(name = "pricing_version", length = 20, comment = "비용 계산에 쓴 단가표 버전 (operations.ai.pricing.version). PENDING 이면 예상치의 버전")
    private String pricingVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "priced_by", length = 16, comment = "단가 출처 — CONFIG(설정 단가) / MAX_FALLBACK(설정 밖 모델, 항목별 최고 단가)")
    private AiPricedBy pricedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16, comment = "상태 (PENDING / COMPLETED / UNUSABLE / FAILED / TIMEOUT / NOT_SENT / ABANDONED)")
    private AiUsageStatus status;

    @Column(name = "reason", length = 32, comment = "실패 원인 (ai-core AiException.Reason). 없으면 null")
    private String reason;

    @Column(name = "http_status", comment = "API 오류 응답의 HTTP 상태 코드. 오류 응답이 아니면 null")
    private Integer httpStatus;

    @Column(name = "message_id", length = 64, comment = "API 메시지 ID. 응답이 없으면 null")
    private String messageId;

    @Column(name = "elapsed_ms", comment = "자리를 잡은 뒤 응답 · 실패까지 걸린 시간 (ms, SDK 재시도 포함). PENDING · ABANDONED 면 null")
    private Integer elapsedMs;

    @Column(name = "called_at", nullable = false, comment = "예약 시각 — 호출 직전")
    private Instant calledAt;

    @Column(name = "settled_at", comment = "닫은 시각 (정산 · 회수). PENDING 이면 null")
    private Instant settledAt;

    /** 감사 시각이 없으면 새 행이다 — SELECT 없이 persist 한다. */
    @Override
    public boolean isNew() {
        return getCreatedAt() == null;
    }
}
