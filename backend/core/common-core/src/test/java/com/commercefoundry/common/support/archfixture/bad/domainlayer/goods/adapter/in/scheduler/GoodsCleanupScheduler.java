package com.commercefoundry.common.support.archfixture.bad.domainlayer.goods.adapter.in.scheduler;

import com.commercefoundry.common.support.archfixture.bad.domainlayer.goods.application.mapper.GoodsMapper;
import com.commercefoundry.common.support.archfixture.bad.domainlayer.goods.application.port.out.GoodsReaderPort;
import com.commercefoundry.common.support.archfixture.bad.domainlayer.goods.application.service.GoodsWebFacade;

/** H10 — 스케줄러가 UseCase 대신 Facade · out-port · mapper 를 직접 쓴다. */
public class GoodsCleanupScheduler {

    private final GoodsWebFacade goodsWebFacade;
    private final GoodsReaderPort goodsReaderPort;
    private final GoodsMapper goodsMapper;

    public GoodsCleanupScheduler(GoodsWebFacade goodsWebFacade, GoodsReaderPort goodsReaderPort, GoodsMapper goodsMapper) {
        this.goodsWebFacade = goodsWebFacade;
        this.goodsReaderPort = goodsReaderPort;
        this.goodsMapper = goodsMapper;
    }

    public String cleanUp() {
        goodsWebFacade.getGoods(0);
        return goodsMapper.nameOf(goodsReaderPort.findGoods(0));
    }
}
