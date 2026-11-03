package com.commercefoundry.domainlayer.product.application.port.out;

import com.commercefoundry.domainlayer.product.application.port.out.query.StockAlertQueryResult;
import com.commercefoundry.domainlayer.product.domain.model.StockAlertPolicy;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;

public interface StockAlertPolicyRepositoryPort {

    Optional<StockAlertPolicy> findBySkuId(long skuId);

    Page<StockAlertQueryResult> findLowStocks(int page, int size);

    /** SKU 쓰기 잠금과 같은 트랜잭션 안에서 정책만 저장한다. 재고 수량은 변경하지 않는다. */
    StockAlertPolicy save(StockAlertPolicy policy);

    /** SKU 쓰기 잠금 안에서만 호출한다. 미설정이면 아무것도 하지 않는다. */
    void delete(long skuId);
    /** 아이디 오름차순으로 limit개까지 읽는다. count 쿼리는 실행하지 않는다. */
    List<StockAlertQueryResult> findLowStocksAfter(long afterSkuId, int limit);
}
