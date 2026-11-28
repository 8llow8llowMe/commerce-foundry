package com.commercefoundry.domainlayer.settlement.domain.model;

import com.commercefoundry.shared.commerce.CurrencyCode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * PG 매출일 하루의 정산 요약 (#219). 매출일마다 한 행이고 같은 날짜를 다시 돌리면 같은 행을 바꾼다. 상태는 아래 표의 도메인 메서드로만 바뀐다(setter 가 없다).
 *
 * <table>
 *   <caption>허용 전이 (표 밖은 {@link SettlementTransitionException})</caption>
 *   <tr><th>현재</th><th>메서드</th><th>다음</th></tr>
 *   <tr><td>(없음)</td><td>{@link #collected}</td><td>COLLECTED</td></tr>
 *   <tr><td>(없음)</td><td>{@link #failed}</td><td>FAILED</td></tr>
 *   <tr><td>COLLECTED · RECONCILED · FAILED</td><td>{@link #recollect}</td><td>COLLECTED (다시 수집)</td></tr>
 *   <tr><td>COLLECTED</td><td>{@link #reconcile}</td><td>RECONCILED</td></tr>
 *   <tr><td>COLLECTED · RECONCILED · FAILED</td><td>{@link #fail}</td><td>FAILED (요약 칸은 마지막 수집 값 그대로)</td></tr>
 * </table>
 *
 * <p>대사는 수집 직후의 COLLECTED 에서만 한다 — 그 사이 다른 실행이 실패로 바꿨거나 이미 대사했으면 이 실행의 수집 결과가 아니라서다.
 *
 * @param id 아이디 (Snowflake)
 * @param soldDate PG 매출일 (KST)
 * @param itemCount 마지막 수집의 거래 수 ({@link #currency} 거래만)
 * @param pgAmountTotal 마지막 수집의 거래 금액 합 — 승인 +, 취소 −
 * @param feeTotal 마지막 수집의 수수료 합
 * @param payOutTotal 마지막 수집의 지급 금액 합
 * @param currency 요약 통화 — 지금은 KRW 만이다(USD 는 #738)
 * @param mismatchCount 마지막 대사가 본 결제들의 미해결(OPEN) 불일치 수
 * @param collectedAt 마지막 수집 성공 시각. 한 번도 수집하지 못했으면 null
 * @param reconciledAt 마지막 대사 시각. 한 번도 대사하지 않았으면 null
 * @param version 낙관적 락 버전. 아직 저장하지 않았으면 null
 */
public record SettlementDay(
    long id, LocalDate soldDate, SettlementDayStatus status, int itemCount, long pgAmountTotal, long feeTotal, long payOutTotal, CurrencyCode currency,
    int mismatchCount, Instant collectedAt, Instant reconciledAt, Long version
) {

    public SettlementDay {
        Objects.requireNonNull(soldDate, "soldDate");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(currency, "currency");
        if (itemCount < 0 || mismatchCount < 0) {
            throw new IllegalArgumentException("거래 수 · 불일치 수는 음수일 수 없습니다");
        }
        if (status == SettlementDayStatus.COLLECTED && collectedAt == null) {
            throw new IllegalArgumentException("수집 완료에는 수집 시각이 있어야 합니다");
        }
        if (status == SettlementDayStatus.RECONCILED && (collectedAt == null || reconciledAt == null)) {
            throw new IllegalArgumentException("대사 완료에는 수집 · 대사 시각이 있어야 합니다");
        }
    }

    /** 처음 수집한 매출일. */
    public static SettlementDay collected(long id, LocalDate soldDate, SettlementCollectionSummary summary, Instant now) {
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(now, "now");
        return new SettlementDay(id, soldDate, SettlementDayStatus.COLLECTED, summary.itemCount(), summary.pgAmountTotal(), summary.feeTotal(),
            summary.payOutTotal(), summary.currency(), 0, now, null, null);
    }

    /** 처음 수집부터 실패한 매출일 — 요약 칸은 0 이다. */
    public static SettlementDay failed(long id, LocalDate soldDate, CurrencyCode currency) {
        return new SettlementDay(id, soldDate, SettlementDayStatus.FAILED, 0, 0, 0, 0, currency, 0, null, null, null);
    }

    /** 다시 수집했다 — 요약 칸을 이번 수집 값으로 바꾼다. 불일치 수 · 대사 시각은 다음 대사가 바꿀 때까지 그대로다. */
    public SettlementDay recollect(SettlementCollectionSummary summary, Instant now) {
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(now, "now");
        if (summary.currency() != currency) {
            throw new IllegalArgumentException("요약 통화가 다릅니다");
        }
        return new SettlementDay(id, soldDate, SettlementDayStatus.COLLECTED, summary.itemCount(), summary.pgAmountTotal(), summary.feeTotal(),
            summary.payOutTotal(), currency, mismatchCount, now, reconciledAt, version);
    }

    /**
     * 대사를 마쳤다.
     *
     * @param openMismatchCount 이번 대사가 본 결제들의 미해결 불일치 수
     * @throws SettlementTransitionException COLLECTED 가 아닐 때
     */
    public SettlementDay reconcile(int openMismatchCount, Instant now) {
        Objects.requireNonNull(now, "now");
        if (status != SettlementDayStatus.COLLECTED) {
            throw new SettlementTransitionException(status, "reconcile");
        }
        return new SettlementDay(id, soldDate, SettlementDayStatus.RECONCILED, itemCount, pgAmountTotal, feeTotal, payOutTotal, currency,
            openMismatchCount, collectedAt, now, version);
    }

    /** 마지막 수집이 실패했다. 요약 칸은 마지막 성공 값 그대로 둔다. */
    public SettlementDay fail() {
        return new SettlementDay(id, soldDate, SettlementDayStatus.FAILED, itemCount, pgAmountTotal, feeTotal, payOutTotal, currency,
            mismatchCount, collectedAt, reconciledAt, version);
    }
}
