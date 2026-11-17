package com.commercefoundry.domainlayer.product.application.port.in;

import com.commercefoundry.domainlayer.product.application.model.PriceBackfillModel.Mode;
import com.commercefoundry.domainlayer.product.application.model.PriceBackfillModel.Report;

/**
 * 기준통화 가격행 backfill (#20 PR-2a). 기동 runner 만 부른다. 호출자 트랜잭션 밖에서 호출한다 — 상품마다 자체 TX 를 쓴다.
 * 사전 점검이 BLOCKED 면 DML 없이 끝난다. 실제 currency 그대로 빠진 기준통화 행만 넣고 기존 행 · 통화는 고치지 않는다.
 */
public interface PriceBackfillUseCase {

    /**
     * @throws com.commercefoundry.domainlayer.product.application.exception.PriceMigrationReadException 읽기 실패(재시도 없음)
     * @throws com.commercefoundry.domainlayer.product.application.exception.PriceMigrationPreflightException 스캔 상한 초과 · Port 계약 위반
     */
    Report run(Mode mode);
}
