package com.commercefoundry.global.properties;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 외부 확인 환불 기록 설정 ({@code payment-guide.md} §8 "외부 확인 환불 기록 명령", #693).
 *
 * <p>이 claimId 의 {@code PENDING} 은 생성 뒤 {@code PaymentCancel.PG_RETRY_WINDOW}(14일) + {@code pendingCallMargin} 이 지나야 외부 확인 환불로 바꿀 수 있다.
 * 결제 후 취소 준비는 생성 14일 안의 {@code PENDING} 에만 PG 취소를 다시 보내고, 그 PG 호출은 준비 직후 시작해 토스 클라이언트 타임아웃(연결 2초 + 읽기 6초,
 * 재시도 없음)이면 끝난다. 여유는 그 최대 호출 시간보다 훨씬 길게 잡는다 — 그 안에서는 PG 취소가 아직 진행 중일 수 있어 기록하면 이중 환불이 된다.
 *
 * @param pendingCallMargin 14일 뒤에 더 기다리는 시간 (기본 1시간). 1시간 미만은 기동 실패다
 */
@ConfigurationProperties(prefix = "payment.cancellation.external-refund")
public record PaymentCancelExternalRefundProperties(Duration pendingCallMargin) {

    /** 하한 겸 기본값 — PG 취소 호출 최대 시간(약 8초)보다 넉넉하게 둔다. */
    public static final Duration MIN_PENDING_CALL_MARGIN = Duration.ofHours(1);

    public PaymentCancelExternalRefundProperties {
        if (pendingCallMargin == null) {
            pendingCallMargin = MIN_PENDING_CALL_MARGIN;
        }
        if (pendingCallMargin.compareTo(MIN_PENDING_CALL_MARGIN) < 0) {
            throw new IllegalArgumentException("payment.cancellation.external-refund.pending-call-margin 은 1시간 이상이어야 합니다");
        }
    }
}
