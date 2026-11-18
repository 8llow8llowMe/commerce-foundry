package com.commercefoundry.domainlayer.product.application.port.out;

import com.commercefoundry.domainlayer.product.application.port.out.query.PriceSourceSnapshotQueryResult;
import java.util.List;

/** 가격 투영 전 원천을 한 읽기 snapshot으로 조회한다. 생산 읽기·쓰기 경로에는 아직 연결하지 않는다. */
public interface PriceSourceSnapshotRepositoryPort {
    PriceSourceSnapshotQueryResult readByProductIds(List<Long> productIds);
}
