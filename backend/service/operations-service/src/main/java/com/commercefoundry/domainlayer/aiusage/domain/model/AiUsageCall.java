package com.commercefoundry.domainlayer.aiusage.domain.model;

import com.commercefoundry.domainlayer.aiusage.domain.model.AiPriceTable.AiPriceQuote;
import java.time.Instant;
import java.util.Objects;

/**
 * 원장의 AI 호출 한 건. 예약하면 {@link #pending} 으로 만들고, 결과를 받으면 {@link #settle}, 결과가 끝내 오지 않으면 {@link #abandon} 으로 닫는다.
 * 닫힌 호출은 다시 바뀌지 않는다 — 저장은 "아직 PENDING 일 때만" 조건부 UPDATE 라 같은 결과가 두 번 와도(또는 회수와 겹쳐도) 한 번만 반영된다.
 *
 * <p>원문 · 개인정보는 없다 — 기능 이름 · 글자 수 · 이미지 장수 · 토큰 수 · 식별자({@code callerRef})만이다.
 *
 * @param period             비용을 매기는 월 — 예약 시각 기준. 정산이 다음 달에 와도 이 월에 매긴다
 * @param estimateCostMicros 예약한 예상 비용 (micro-USD)
 * @param usage              실측 토큰. 응답을 받지 못했으면 0
 * @param costMicros         확정 비용 (micro-USD). PENDING 이면 {@code null}
 * @param pricingVersion     비용 계산에 쓴 단가표 버전 — PENDING 이면 예상치의 버전이다
 * @param pricedBy           비용 계산에 쓴 단가 출처 — PENDING 이면 예상치의 출처다
 */
public record AiUsageCall(
    long id,
    AiBudgetPeriod period,
    String feature,
    Integer promptVersion,
    String callerRef,
    String requestedModel,
    String model,
    boolean structured,
    int imageCount,
    int inputChars,
    long estimateCostMicros,
    AiTokenUsage usage,
    Long costMicros,
    String pricingVersion,
    AiPricedBy pricedBy,
    AiUsageStatus status,
    String reason,
    Integer httpStatus,
    String messageId,
    Integer elapsedMs,
    Instant calledAt,
    Instant settledAt
) {

    public static final int FEATURE_MAX_LENGTH = 63;
    public static final int CALLER_REF_MAX_LENGTH = 100;
    public static final int MODEL_MAX_LENGTH = 64;
    public static final int REASON_MAX_LENGTH = 32;
    public static final int MESSAGE_ID_MAX_LENGTH = 64;
    public static final int PRICING_VERSION_MAX_LENGTH = 20;

    public AiUsageCall {
        if (id <= 0) {
            throw new IllegalArgumentException("id");
        }
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(usage, "usage");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(calledAt, "calledAt");
        requireText(feature, FEATURE_MAX_LENGTH, "feature");
        requireText(requestedModel, MODEL_MAX_LENGTH, "requestedModel");
        requireLength(callerRef, CALLER_REF_MAX_LENGTH, "callerRef");
        requireLength(model, MODEL_MAX_LENGTH, "model");
        requireLength(reason, REASON_MAX_LENGTH, "reason");
        requireLength(pricingVersion, PRICING_VERSION_MAX_LENGTH, "pricingVersion");
        if (imageCount < 0 || inputChars < 0 || estimateCostMicros < 0 || costMicros != null && costMicros < 0) {
            throw new IllegalArgumentException("글자 수 · 이미지 장수 · 비용은 음수일 수 없습니다.");
        }
        if (status.isPending() != (costMicros == null) || status.isPending() != (settledAt == null)) {
            throw new IllegalArgumentException("PENDING 만 비용 · 정산 시각이 비어 있습니다.");
        }
    }

    /**
     * 예약한 호출.
     *
     * @param estimateQuote 예상 비용을 계산한 단가 — 버전 · 출처를 남긴다
     */
    public static AiUsageCall pending(
        long id, AiBudgetPeriod period, String feature, Integer promptVersion, String callerRef, String requestedModel, boolean structured,
        int imageCount, long inputChars, long estimateCostMicros, AiPriceQuote estimateQuote, Instant calledAt
    ) {
        Objects.requireNonNull(estimateQuote, "estimateQuote");
        return new AiUsageCall(
            id, period, feature, promptVersion, callerRef, requestedModel, null, structured, imageCount, Math.toIntExact(inputChars), estimateCostMicros,
            AiTokenUsage.ZERO, null, estimateQuote.version(), estimateQuote.pricedBy(), AiUsageStatus.PENDING, null, null, null, null, calledAt, null
        );
    }

    /**
     * 결과를 받아 닫는다. 비용은 {@link AiUsageStatus#settledCostMicros} 규칙이다.
     *
     * @param outcome     호출 결과 — PENDING · ABANDONED 는 받지 않는다
     * @param usage       실측 토큰 — 응답을 받은 결과({@link AiUsageStatus#chargesActualUsage()})에만 있다. 나머지는 무시하고 0 으로 둔다
     * @param model       답한 모델. 없으면 요청한 모델. 64자를 넘으면 자른다(메시지 ID · 원인도 칸 길이로 자른다) — 정산은 길이로 실패하지 않는다
     * @param actualQuote 실측 비용을 계산할 단가 — 응답을 받은 결과에만 필요하다
     * @param elapsedMs   걸린 시간. int 범위를 넘으면 상한으로 자른다
     * @throws IllegalStateException 이미 닫힌 호출이다
     */
    public AiUsageCall settle(
        AiUsageStatus outcome, AiTokenUsage usage, String model, AiPriceQuote actualQuote, String reason, Integer httpStatus, String messageId, long elapsedMs,
        Instant now
    ) {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(now, "now");
        requirePending();
        if (!outcome.isOutcome()) {
            throw new IllegalArgumentException("호출 결과가 아닌 상태입니다: " + outcome);
        }
        AiTokenUsage settledUsage = AiTokenUsage.ZERO;
        long actualMicros = 0;
        String settledPricingVersion = pricingVersion;
        AiPricedBy settledPricedBy = pricedBy;
        if (outcome.chargesActualUsage()) {
            Objects.requireNonNull(usage, "응답을 받은 결과에는 사용량이 있어야 합니다.");
            Objects.requireNonNull(actualQuote, "응답을 받은 결과에는 단가가 있어야 합니다.");
            settledUsage = usage;
            actualMicros = AiCost.micros(usage, actualQuote.price());
            settledPricingVersion = actualQuote.version();
            settledPricedBy = actualQuote.pricedBy();
        }
        return new AiUsageCall(
            id, period, feature, promptVersion, callerRef, requestedModel, model == null ? requestedModel : truncate(model, MODEL_MAX_LENGTH), structured,
            imageCount, inputChars, estimateCostMicros, settledUsage, outcome.settledCostMicros(actualMicros, estimateCostMicros), settledPricingVersion,
            settledPricedBy, outcome, truncate(reason, REASON_MAX_LENGTH), httpStatus, truncate(messageId, MESSAGE_ID_MAX_LENGTH),
            (int) Math.min(Math.max(elapsedMs, 0L), Integer.MAX_VALUE), calledAt, now
        );
    }

    /** 결과가 끝내 오지 않았다 — 예상치를 비용으로 남기고 닫는다. */
    public AiUsageCall abandon(Instant now) {
        Objects.requireNonNull(now, "now");
        requirePending();
        return new AiUsageCall(
            id, period, feature, promptVersion, callerRef, requestedModel, model, structured, imageCount, inputChars, estimateCostMicros, usage,
            AiUsageStatus.ABANDONED.settledCostMicros(0, estimateCostMicros), pricingVersion, pricedBy, AiUsageStatus.ABANDONED, null, null, null, null,
            calledAt, now
        );
    }

    private void requirePending() {
        if (!status.isPending()) {
            throw new IllegalStateException("이미 닫힌 호출입니다: " + status);
        }
    }

    private static void requireText(String value, int maxLength, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " 이 비었습니다.");
        }
        requireLength(value, maxLength, name);
    }

    private static void requireLength(String value, int maxLength, String name) {
        if (value != null && value.length() > maxLength) {
            throw new IllegalArgumentException(name + " 은 " + maxLength + "자 이하여야 합니다.");
        }
    }

    private static String truncate(String value, int maxLength) {
        return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
