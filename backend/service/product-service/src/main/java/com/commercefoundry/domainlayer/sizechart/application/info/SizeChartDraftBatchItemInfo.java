package com.commercefoundry.domainlayer.sizechart.application.info;

import java.util.UUID;

/** 오류 원문과 입력은 포함하지 않는다. draftId는 등록 응답 유실 시 복구용으로 남긴다. */
public record SizeChartDraftBatchItemInfo(long productId, UUID idempotencyKey, Long draftId, Long jobId, String errorCode, boolean retryable) {
}
