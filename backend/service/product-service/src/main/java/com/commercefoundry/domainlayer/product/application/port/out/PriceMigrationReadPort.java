package com.commercefoundry.domainlayer.product.application.port.out;

import com.commercefoundry.domainlayer.product.application.port.out.query.PriceMigrationReadQueryResult.Bounds;
import com.commercefoundry.domainlayer.product.application.model.PriceMigrationReadModel.Criteria;
import com.commercefoundry.domainlayer.product.application.port.out.query.PriceMigrationReadQueryResult.Row;
import java.util.List;

/** 생산 caller 미연결. 네 원천의 독립 PK를 읽으며 전체 실행의 동일 시점 snapshot은 보장하지 않는다. */
public interface PriceMigrationReadPort {
    Bounds captureBounds();
    List<Row> readPage(Criteria criteria);
}
