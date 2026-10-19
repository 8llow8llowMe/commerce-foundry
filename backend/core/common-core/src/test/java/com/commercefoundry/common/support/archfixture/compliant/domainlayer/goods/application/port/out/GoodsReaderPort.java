package com.commercefoundry.common.support.archfixture.compliant.domainlayer.goods.application.port.out;

import com.commercefoundry.common.support.archfixture.compliant.domainlayer.goods.application.port.out.query.GoodsQueryResult;
import com.commercefoundry.common.support.archfixture.compliant.domainlayer.goods.domain.model.Goods;

/** out-port 는 QueryResult · domain 을 반환한다. */
public interface GoodsReaderPort {

    Goods findGoods(long goodsId);

    GoodsQueryResult findSummary(long goodsId);
}
