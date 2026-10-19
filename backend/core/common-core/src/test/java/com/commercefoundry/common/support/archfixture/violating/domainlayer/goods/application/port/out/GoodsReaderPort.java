package com.commercefoundry.common.support.archfixture.violating.domainlayer.goods.application.port.out;

import com.commercefoundry.common.support.archfixture.violating.domainlayer.goods.application.info.GoodsInfo;

/** H5 — out-port 가 Info 를 반환한다. */
public interface GoodsReaderPort {

    GoodsInfo findGoods(long goodsId);
}
