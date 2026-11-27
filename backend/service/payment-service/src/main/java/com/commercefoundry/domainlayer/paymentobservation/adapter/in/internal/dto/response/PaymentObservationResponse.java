package com.commercefoundry.domainlayer.paymentobservation.adapter.in.internal.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "결제 이상 후보 관측 원천 (임계값 판정 및 상태 체류시간 아님)")
public record PaymentObservationResponse(
    @Schema(description = "단일 기준 관측 시각 (UTC, 마이크로초)")
    Instant observedAt,

    @Schema(description = "결과 미확정 전체 (수동 검토 포함)")
    Bucket unknown,

    @Schema(description = "결과 미확정 중 수동 검토 부분집합 (unknown과 합산 금지)")
    Bucket manualReviewUnknown,

    @Schema(description = "승인 진행 중")
    Bucket inProgress,

    @Schema(description = "승인 요청 후 READY로 복귀한 후보")
    Bucket returnedReady,

    @Schema(description = "늦은 승인")
    Bucket lateApproved,

    @Schema(description = "주문 반영 대기 완료 결제")
    Bucket doneOrderUnsynced,

    @Schema(description = "자동 취소 수동 검토 대기 (앞 여섯 버킷과 다른 스냅숏일 수 있고 manualReviewUnknown과 합산 금지). "
        + "자동 취소 게이트가 꺼져 관측하지 않았으면 null (0건 아님)", nullable = true)
    Bucket autoCancelManualReview
) {
    public record Bucket(
        @Schema(description = "후보 결제 시도 수")
        long count,

        @Schema(description = "앞 네 버킷은 마지막 승인 요청 시작 시각, 다음 두 버킷은 PG 승인 시각, autoCancelManualReview는 마지막 실행 예정·획득 시각"
            + "(수동 검토 진입 시각 아님)의 최솟값. 후보가 없으면 null", nullable = true)
        Instant oldestAt
    ) { }
}
