package com.commercefoundry.domainlayer.product.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.product.application.port.out.PriceSourceSnapshotRepositoryPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 서비스 간 SKU 조회(PRICE_BOOK)의 snapshot 묶음 크기 불변식(#20 B4b). 세 상수가 서로 다른 클래스에 있어 하나만 바뀌면 정상 데이터에서도
 * {@code ROW_LIMIT_EXCEEDED} · {@code INVALID_INPUT} 500 이 나고 order-service 서킷 실패로 세어진다 — 이 시험이 그 관계를 고정한다.
 * 포트 상한은 포트 계약 상수라 application 시험이 adapter 를 참조하지 않는다.
 */
class SkuInternalSnapshotChunkLimitTest {

    @Test
    @DisplayName("묶음 상품 수 × 상품당 SKU 상한(SKU_ADMIN_010 · PRODUCT_095)은 snapshot 포트의 SKU 행 상한 이하다")
    void chunkTimesSkusPerProductFitsSkuRowLimit() {
        long worstCaseSkuRows = (long) SkuInternalProcessor.SNAPSHOT_CHUNK_PRODUCTS * SkuAddProcessor.MAX_SKUS;

        assertThat(worstCaseSkuRows).isLessThanOrEqualTo(PriceSourceSnapshotRepositoryPort.MAX_SKU_ROWS);
    }

    @Test
    @DisplayName("묶음 상품 수는 snapshot 포트의 상품 상한 이하다")
    void chunkFitsProductLimit() {
        assertThat(SkuInternalProcessor.SNAPSHOT_CHUNK_PRODUCTS).isPositive().isLessThanOrEqualTo(PriceSourceSnapshotRepositoryPort.MAX_PRODUCTS);
    }
}
