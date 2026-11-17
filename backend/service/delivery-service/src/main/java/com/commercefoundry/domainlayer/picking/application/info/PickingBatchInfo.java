package com.commercefoundry.domainlayer.picking.application.info;

import com.commercefoundry.domainlayer.picking.domain.model.PickingBatch;

/** 출고 묶음 처리 결과(생성 · 상세 · 검수). 검수 완료 여부는 출고 완료 여부가 아니다. */
public record PickingBatchInfo(PickingBatch batch) {
}
