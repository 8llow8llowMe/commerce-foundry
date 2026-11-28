package com.commercefoundry.domainlayer.settlement.domain.model;

import com.commercefoundry.shared.commerce.CurrencyCode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * 정산 대사 불일치 하나 (#219). (결제키, 종류)마다 한 행이다 — 같은 날짜 · 다른 날짜의 재대사가 같은 불일치를 다시 찾아도 행이 늘지 않는다.
 * 상태는 아래 표의 도메인 메서드로만 바뀐다(setter 가 없다).
 *
 * <table>
 *   <caption>허용 전이 (표 밖은 {@link SettlementTransitionException})</caption>
 *   <tr><th>현재</th><th>메서드</th><th>다음</th></tr>
 *   <tr><td>(없음)</td><td>{@link #open}</td><td>OPEN</td></tr>
 *   <tr><td>OPEN · RESOLVED</td><td>{@link #redetect}</td><td>OPEN (금액 · 발견 시각만 바뀐다, RESOLVED 면 다시 연다)</td></tr>
 *   <tr><td>OPEN</td><td>{@link #autoClear}</td><td>RESOLVED (AUTO_CLEARED)</td></tr>
 * </table>
 *
 * <p>사람이 닫는 전이(MANUAL)는 어드민 API(#219 PR3)가 더한다.
 *
 * @param id 아이디 (Snowflake)
 * @param soldDate 처음 발견한 대사의 매출일 — 다시 발견해도 바뀌지 않는다
 * @param paymentKey PG 결제키. 로그 · {@link #toString()} 에는 앞 8자만 남긴다
 * @param paymentId 결제 시도 아이디 — 결제 기록 누락(MISSING_IN_LEDGER)이면 null
 * @param type 종류
 * @param status 처리 상태
 * @param resolution 닫은 방법 — OPEN 이면 null
 * @param expectedAmount 우리 기록 쪽 금액
 * @param actualAmount PG 쪽 금액
 * @param currency 통화
 * @param detectedAt 마지막으로 발견한 시각
 * @param resolvedAt 닫은 시각 — OPEN 이면 null
 * @param version 낙관적 락 버전. 아직 저장하지 않았으면 null
 */
public record SettlementMismatch(
    long id, LocalDate soldDate, String paymentKey, Long paymentId, SettlementMismatchType type, SettlementMismatchStatus status,
    SettlementMismatchResolution resolution, long expectedAmount, long actualAmount, CurrencyCode currency, Instant detectedAt, Instant resolvedAt,
    Long version
) {

    public SettlementMismatch {
        Objects.requireNonNull(soldDate, "soldDate");
        Objects.requireNonNull(paymentKey, "paymentKey");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(detectedAt, "detectedAt");
        if ((type == SettlementMismatchType.MISSING_IN_LEDGER) != (paymentId == null)) {
            throw new IllegalArgumentException("결제 기록 누락에만 결제 아이디가 없습니다");
        }
        boolean open = status == SettlementMismatchStatus.OPEN;
        if (open != (resolution == null) || open != (resolvedAt == null)) {
            throw new IllegalArgumentException("미해결이면 해결 방법 · 시각이 없고, 해결이면 둘 다 있어야 합니다");
        }
    }

    /** 처음 찾은 불일치. */
    public static SettlementMismatch open(long id, LocalDate soldDate, SettlementDetection detection, CurrencyCode currency, Instant now) {
        Objects.requireNonNull(detection, "detection");
        return new SettlementMismatch(id, soldDate, detection.paymentKey(), detection.paymentId(), detection.type(), SettlementMismatchStatus.OPEN, null,
            detection.expectedAmount(), detection.actualAmount(), currency, now, null, null);
    }

    /** 다시 찾았다 — 금액 · 발견 시각을 바꾸고, 닫혀 있었으면 다시 연다. */
    public SettlementMismatch redetect(SettlementDetection detection, Instant now) {
        Objects.requireNonNull(detection, "detection");
        if (!detection.paymentKey().equals(paymentKey) || detection.type() != type) {
            throw new IllegalArgumentException("다른 결제키 · 종류의 판정입니다");
        }
        return new SettlementMismatch(id, soldDate, paymentKey, paymentId, type, SettlementMismatchStatus.OPEN, null,
            detection.expectedAmount(), detection.actualAmount(), currency, now, null, version);
    }

    /**
     * 다시 대사했을 때 더는 어긋나지 않는다.
     *
     * @throws SettlementTransitionException OPEN 이 아닐 때
     */
    public SettlementMismatch autoClear(Instant now) {
        Objects.requireNonNull(now, "now");
        if (status != SettlementMismatchStatus.OPEN) {
            throw new SettlementTransitionException(status, "autoClear");
        }
        return new SettlementMismatch(id, soldDate, paymentKey, paymentId, type, SettlementMismatchStatus.RESOLVED,
            SettlementMismatchResolution.AUTO_CLEARED, expectedAmount, actualAmount, currency, detectedAt, now, version);
    }

    public boolean isOpen() {
        return status == SettlementMismatchStatus.OPEN;
    }

    /** 결제키 전체를 로그에 남기지 않는다 ({@code coding-conventions.md} §8). */
    @Override
    public String toString() {
        return "SettlementMismatch[id=%d, soldDate=%s, paymentKey=%s, paymentId=%s, type=%s, status=%s, resolution=%s, version=%s]".formatted(
            id, soldDate, PgSettlementItem.maskPaymentKey(paymentKey), paymentId, type, status, resolution, version);
    }
}
