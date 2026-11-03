package com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.custom;

import com.commercefoundry.domainlayer.product.application.port.out.query.StockAlertQueryResult;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface StockAlertPolicyCustomRepository {

    Page<StockAlertQueryResult> findLowStocks(Pageable pageable);
    /** 아이디 오름차순으로 limit개까지 읽는다. count 쿼리는 실행하지 않는다. */
    List<StockAlertQueryResult> findLowStocksAfter(long afterSkuId, int limit);
}
