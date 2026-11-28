package com.commercefoundry.domainlayer.product.application.port.out;

import com.commercefoundry.domainlayer.product.application.port.out.query.PriceSourceSnapshotQueryResult;
import java.util.List;

/**
 * 가격 투영 전 원천(상품 · SKU · 상품 가격행 · SKU 가격행)을 한 읽기 snapshot으로 조회한다 — 네 원천이 같은 시점의 값이다.
 * 두 메서드는 입력 정규화·상한·조회 순서·예외 정제가 같고 트랜잭션 경계만 다르다. 실패는 원인 없이 사유만 담은
 * {@code PriceSourceSnapshotReadException}(INVALID_INPUT · PRODUCT_MISSING · ROW_LIMIT_EXCEEDED · READ_FAILED · CALLER_TRANSACTION_REQUIRED)이다.
 */
public interface PriceSourceSnapshotRepositoryPort {

    /** 호출자 TX를 정지하고 새 REPEATABLE_READ read-only TX에서 읽는다 — snapshot 은 그 새 TX 의 시점이다. */
    PriceSourceSnapshotQueryResult readByProductIds(List<Long> productIds);

    /**
     * 호출자 TX에 참여해 같은 커넥션·같은 snapshot으로 읽는다 — 호출자가 먼저 읽은 상품·SKU와 한 시점이다(#20 D3).
     * 호출자 TX가 없거나 read-only가 아니거나 격리 수준이 REPEATABLE_READ·SERIALIZABLE이 아니면 SQL 없이
     * {@code CALLER_TRANSACTION_REQUIRED}로 거절한다. 빈 입력은 TX 확인 없이 불변 빈 결과다.
     */
    PriceSourceSnapshotQueryResult readByProductIdsInCurrentTransaction(List<Long> productIds);
}
