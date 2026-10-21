package com.commercefoundry.domainlayer.payment.domain.model;

import com.commercefoundry.common.dto.metadata.CodeNameDescribable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 결제 시도의 상태 ({@code payment-guide.md} §5). 전이는 {@link Payment} 의 도메인 메서드로만 일어난다 — 허용 전이 표는 {@link Payment} 에 있다.
 *
 * <pre>
 * READY ──▶ IN_PROGRESS ──▶ DONE ──▶ (PARTIAL_CANCELED ──▶ CANCELED — 취소 이슈에서 연다)
 *   │   ◀──────┘ │  │
 *   │            │  └──▶ FAILED
 *   │            └──▶ UNKNOWN ──▶ DONE | FAILED   (PG 재조회로 확정)
 *   └──▶ ABORTED
 * </pre>
 *
 * <p>{@link #PARTIAL_CANCELED} · {@link #CANCELED} 는 아직 들어오는 전이가 없다. 그래도 지금 둔다 — Hibernate 가 {@code status} 컬럼에
 * {@code check (status in (...))} 를 만들고 {@code ddl-auto=update} 는 그 제약을 고치지 않으므로, 값을 나중에 더하면 운영 · dev 에 drop / add DDL 이 따로 필요하다
 * ({@code coding-conventions.md} §9-8).
 */
@Getter
@RequiredArgsConstructor
public enum PaymentStatus implements CodeNameDescribable {

    READY("결제 대기", "결제를 준비했고 고객의 결제 인증을 기다리고 있습니다."),
    IN_PROGRESS("결제 승인 중", "결제사에 승인을 요청하고 있습니다."),
    DONE("결제 완료", "결제가 승인되었습니다."),
    FAILED("결제 실패", "결제사가 승인을 거절했습니다. 다시 결제할 수 있습니다."),
    UNKNOWN("결제 확인 중", "승인 결과를 아직 확인하지 못했습니다. 결제사에 다시 조회해 확정합니다."),
    ABORTED("결제 중단", "주문 금액 변경 · 결제 재준비 · 주문 만료로 버려진 결제 시도입니다."),
    PARTIAL_CANCELED("부분 취소", "승인 금액 중 일부가 취소되었습니다."),
    CANCELED("결제 취소", "승인 금액이 모두 취소되었습니다.");

    private final String displayName;
    private final String description;

    /** 승인된 적이 있는 상태 — 결제키 · 승인 시각이 있고, 주문 반영 여부를 기록한다. */
    public boolean isApproved() {
        return this == DONE || this == PARTIAL_CANCELED || this == CANCELED;
    }

    /** 주문의 활성 결제 슬롯을 놓은 상태 — 같은 주문에 새 결제 시도를 만들 수 있다 ({@link Payment#activeOrderId()}). */
    public boolean releasesActiveSlot() {
        return this == FAILED || this == ABORTED;
    }
}
