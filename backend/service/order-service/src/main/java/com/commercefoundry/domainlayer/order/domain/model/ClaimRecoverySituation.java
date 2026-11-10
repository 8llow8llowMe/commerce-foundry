package com.commercefoundry.domainlayer.order.domain.model;

import com.commercefoundry.common.dto.metadata.CodeNameDescribable;
import java.util.Objects;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 어드민 운영자에게 보이는 결제 후 취소 클레임의 처리 상황 (#17 A2b). 운영자는 비개발자라 원격 오류 코드 · 시도 횟수 대신 이 업무 언어로 본다 —
 * 원시 실패 코드는 로그(클레임 · 주문 · 결제 아이디)에만 남긴다.
 */
@Getter
@RequiredArgsConstructor
public enum ClaimRecoverySituation implements CodeNameDescribable {
    NO_ACTION("조치 필요 없음", "처리가 끝났거나 운영자가 손댈 일이 없는 접수입니다."),
    AUTO_PROCESSING("자동 처리 중", "결제 취소 또는 재고 복원을 자동으로 진행하고 있습니다. 다음 자동 시도 시각에 다시 확인합니다."),
    REFUND_UNCONFIRMED("결제 취소 결과 미확인", "결제 취소 결과를 끝내 확인하지 못했습니다. 다시 시도하면 같은 결제 취소를 한 번 더 확인합니다."),
    REFUND_REJECTED("결제 취소 거절", "결제사가 결제 취소를 받지 않았습니다. 결제 관리 화면에서 결제 상태를 확인해 주세요."),
    RESTORATION_UNCONFIRMED("재고 복원 결과 미확인", "결제 취소는 끝났지만 재고 복원 결과를 확인하지 못했습니다. 다시 시도하면 같은 복원을 한 번 더 확인합니다."),
    RESTORATION_REJECTED("재고 복원 거절", "결제 취소는 끝났지만 재고를 되돌리지 못했습니다. 재고 관리 화면에서 수량을 확인해 주세요."),
    ORDER_STATUS_MISMATCH("주문 상태 확인 필요", "결제 취소는 끝났지만 주문 상태가 맞지 않아 재고를 되돌리지 않았습니다. 주문 상태를 확인해 주세요.");

    /** {@code OrderCancellationProcessor.ORDER_STATUS_UNEXPECTED} 와 같은 값 — 도메인은 application 상수를 참조하지 않는다({@link ClaimAdminRecoveryPolicy} 와 같다). */
    private static final String ORDER_STATUS_UNEXPECTED = "ORDER_STATUS_UNEXPECTED";

    private final String displayName;
    private final String description;

    public static ClaimRecoverySituation of(Claim claim) {
        Objects.requireNonNull(claim, "claim");
        if (claim.type() != Claim.Type.CANCELLATION) {
            return NO_ACTION;
        }
        return switch (claim.status()) {
            case REQUESTED, REFUNDED -> AUTO_PROCESSING;
            case COMPLETED -> NO_ACTION;
            case REFUND_FAILED -> Claim.REFUND_UNCONFIRMED.equals(claim.failureCode()) ? REFUND_UNCONFIRMED : REFUND_REJECTED;
            case RESTORATION_FAILED -> {
                if (ORDER_STATUS_UNEXPECTED.equals(claim.failureCode())) {
                    yield ORDER_STATUS_MISMATCH;
                }
                yield Claim.RESTORATION_UNCONFIRMED.equals(claim.failureCode()) ? RESTORATION_UNCONFIRMED : RESTORATION_REJECTED;
            }
        };
    }
}
