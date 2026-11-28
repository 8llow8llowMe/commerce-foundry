package com.commercefoundry.domainlayer.settlement.domain.model;

import com.commercefoundry.shared.commerce.CurrencyCode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * PG 정산 거래 1건 (#219, {@code payment-guide.md} §10). 거래키({@link #transactionKey})가 업무 키다 — 같은 거래를 다시 수집하면 같은 행을 갱신한다.
 * 금액 부호는 정규화돼 있다 — 승인 거래는 0 이상, 취소 거래는 0 이하. PG 응답의 부호 표기와 무관하다(정산 어댑터가 맞춘다).
 *
 * @param transactionKey PG 거래키
 * @param paymentKey PG 결제키 — 한 결제의 승인 · 취소 거래가 같은 결제키를 갖는다. 로그 · {@link #toString()} 에는 앞 8자만 남긴다
 * @param pgOrderId PG 주문번호(우리 결제 시도 아이디 문자열). 없으면 null
 * @param soldDate PG 매출일 (KST 날짜)
 * @param paidOutDate 지급(정산) 예정일. 없으면 null
 * @param method 결제수단. 없으면 null
 * @param amount 거래 금액 — 승인 양수, 취소 음수 (최소 화폐 단위)
 * @param feeAmount 수수료 합계 — PG 수수료 항목 공급가의 합. 저장 · 합계만 하고 판정에 쓰지 않는다
 * @param payOutAmount 지급 금액. 저장 · 합계만 하고 판정에 쓰지 않는다
 * @param currency 통화
 * @param canceled 취소 거래인가
 * @param approvedAt PG 승인 시각. 없으면 null
 */
public record PgSettlementItem(
    String transactionKey, String paymentKey, String pgOrderId, LocalDate soldDate, LocalDate paidOutDate, SettlementPaymentMethod method,
    long amount, long feeAmount, long payOutAmount, CurrencyCode currency, boolean canceled, Instant approvedAt
) {

    public static final int TRANSACTION_KEY_MAX_LENGTH = 64;
    public static final int PAYMENT_KEY_MAX_LENGTH = 200;
    public static final int PG_ORDER_ID_MAX_LENGTH = 64;

    private static final int MASKED_PAYMENT_KEY_PREFIX_LENGTH = 8;

    public PgSettlementItem {
        Objects.requireNonNull(transactionKey, "transactionKey");
        Objects.requireNonNull(paymentKey, "paymentKey");
        Objects.requireNonNull(soldDate, "soldDate");
        Objects.requireNonNull(currency, "currency");
        requireText(transactionKey, TRANSACTION_KEY_MAX_LENGTH, "transactionKey");
        requireText(paymentKey, PAYMENT_KEY_MAX_LENGTH, "paymentKey");
        if (pgOrderId != null) {
            requireText(pgOrderId, PG_ORDER_ID_MAX_LENGTH, "pgOrderId");
        }
        if (canceled ? amount > 0 : amount < 0) {
            throw new IllegalArgumentException("거래 금액 부호가 거래 종류와 맞지 않습니다 — 승인은 0 이상, 취소는 0 이하");
        }
    }

    /** 승인 금액 — 승인 거래면 금액, 취소 거래면 0. */
    public long approvedAmount() {
        return canceled ? 0L : amount;
    }

    /** 취소 금액(양수) — 취소 거래면 |금액|, 승인 거래면 0. */
    public long canceledAmount() {
        return canceled ? Math.negateExact(amount) : 0L;
    }

    /** 결제키 전체를 로그에 남기지 않는다 ({@code coding-conventions.md} §8) — 레코드 기본 {@code toString} 은 모든 칸을 그대로 찍는다. */
    @Override
    public String toString() {
        return "PgSettlementItem[paymentKey=%s, soldDate=%s, amount=%d, currency=%s, canceled=%s]"
            .formatted(maskPaymentKey(paymentKey), soldDate, amount, currency, canceled);
    }

    /** 앞 8자 + {@code ***}. 8자 이하면 전부 가린다. null 이면 null. */
    public static String maskPaymentKey(String paymentKey) {
        if (paymentKey == null) {
            return null;
        }
        if (paymentKey.length() <= MASKED_PAYMENT_KEY_PREFIX_LENGTH) {
            return "***";
        }
        return paymentKey.substring(0, MASKED_PAYMENT_KEY_PREFIX_LENGTH) + "***";
    }

    private static void requireText(String value, int maxLength, String name) {
        if (value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(name + " 는 1~" + maxLength + "자여야 합니다");
        }
    }
}
