package com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.custom;

import com.commercefoundry.domainlayer.product.application.port.out.query.StockAlertQueryResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface StockAlertPolicyCustomRepository {

    Page<StockAlertQueryResult> findLowStocks(Pageable pageable);
}
