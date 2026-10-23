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
 *   │  (되돌림)   │  └──▶ FAILED ──┐
 *   │            └──▶ UNKNOWN ──▶ DONE | FAILED   (PG 재조회로 확정)
 *   └──▶ ABORTED ──────────────────┴──▶ LATE_APPROVED   (종결 뒤 PG 승인이 확인됨 — 전액 취소 대상)
 * </pre>
 *
 * <p>{@link #PARTIAL_CANCELED} · {@link #CANCELED} · {@link #WAITING_FOR_DEPOSIT} 는 아직 들어오는 전이가 없다. 그래도 지금 둔다 — Hibernate 가 {@code status} 컬럼에
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
    LATE_APPROVED("늦은 승인", "결제사는 승인했지만 이 결제 시도는 이미 실패 · 중단으로 끝났습니다. 전액 취소 대상입니다."),
    PARTIAL_CANCELED("부분 취소", "승인 금액 중 일부가 취소되었습니다."),
    CANCELED("결제 취소", "승인 금액이 모두 취소되었습니다."),
    WAITING_FOR_DEPOSIT("입금 대기", "가상계좌를 발급했고 고객의 입금을 기다리고 있습니다.");

    private final String displayName;
    private final String description;

    /** PG 가 승인한 적이 있는 상태 — 결제키 · 결제수단 · 승인 시각이 있다. 늦은 승인도 포함한다(돈이 빠졌다). */
    public boolean hasApproval() {
        return this == DONE || this == LATE_APPROVED || this == PARTIAL_CANCELED || this == CANCELED;
    }

    /** 이 시도로 주문을 결제 완료로 반영하는 상태 — 주문 반영 기록({@link Payment#orderSynced()})을 남긴다. 늦은 승인은 주문에 반영하지 않는다. */
    public boolean reportsToOrder() {
        return this == DONE || this == PARTIAL_CANCELED || this == CANCELED;
    }

    /**
     * 주문의 활성 결제 슬롯을 놓은 상태 — 같은 주문에 새 결제 시도를 만들 수 있다 ({@link Payment#activeOrderId()}).
     * 늦은 승인은 이미 종결된 시도라 슬롯을 다시 잡지 않는다 — 그 사이 같은 주문에 다른 시도가 생겼을 수 있다.
     */
    public boolean releasesActiveSlot() {
        return this == FAILED || this == ABORTED || this == LATE_APPROVED;
    }
}
