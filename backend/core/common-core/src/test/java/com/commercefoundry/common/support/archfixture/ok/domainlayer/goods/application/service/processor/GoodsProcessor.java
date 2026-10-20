package com.commercefoundry.common.support.archfixture.ok.domainlayer.goods.application.service.processor;

import com.commercefoundry.common.support.archfixture.ok.domainlayer.catalog.domain.model.Catalog;
import com.commercefoundry.common.support.archfixture.ok.domainlayer.goods.application.info.GoodsInfo;
import com.commercefoundry.common.support.archfixture.ok.domainlayer.goods.application.port.out.GoodsReaderPort;
import com.commercefoundry.common.support.archfixture.ok.domainlayer.goods.domain.model.Goods;

/** 다른 컨텍스트 domain model 은 Facade 가 넘긴 인자로만 받는다. */
public class GoodsProcessor {

    private final GoodsReaderPort goodsReaderPort;

    public GoodsProcessor(GoodsReaderPort goodsReaderPort) {
        this.goodsReaderPort = goodsReaderPort;
    }

    public GoodsInfo goodsOf(long goodsId, Catalog catalog) {
        Goods goods = goodsReaderPort.findGoods(goodsId);
        return new GoodsInfo(goods.id(), goods.name(), catalog.name());
    }
}
