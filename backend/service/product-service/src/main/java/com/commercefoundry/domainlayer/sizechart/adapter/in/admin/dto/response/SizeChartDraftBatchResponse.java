package com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "사이즈표 초안 일괄 제출 결과. 실패 항목은 같은 UUID와 입력으로 재시도합니다")
public record SizeChartDraftBatchResponse(
    @Schema(description = "요청 순서의 개별 항목 결과")
    List<Item> items
) {
    @Schema(description = "개별 초안 접수 또는 실패 결과. jobId가 있으면 접수 완료입니다")
    public record Item(
        @Schema(description = "상품 아이디", example = "4611686018427387904")
        String productId,

        @Schema(description = "이 항목에 제출한 UUID. 실패 항목 재시도 시 그대로 사용합니다")
        String idempotencyKey,

        @Schema(description = "초안 아이디. 등록 여부가 불명확한 실패에서도 복구를 위해 반환할 수 있습니다")
        String draftId,

        @Schema(description = "접수가 확인된 생성 작업 아이디")
        String jobId,

        @Schema(description = "실패 시 안전한 오류 코드. 접수 성공 시 null")
        String errorCode,

        @Schema(description = "같은 운영자·UUID·입력으로 재시도 가능한 일시 오류인지. 멱등 보관 기간 7일 안에서만 재시도합니다")
        boolean retryable
    ) {
    }
}
