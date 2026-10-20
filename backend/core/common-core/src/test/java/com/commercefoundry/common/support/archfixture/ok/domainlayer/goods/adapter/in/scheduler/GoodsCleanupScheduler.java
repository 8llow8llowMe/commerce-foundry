package com.commercefoundry.common.support.archfixture.ok.domainlayer.goods.adapter.in.scheduler;

import com.commercefoundry.common.support.archfixture.ok.domainlayer.goods.application.port.in.GoodsWebUseCase;

/** 스케줄러도 진입이다 — UseCase 를 거친다(H10 허용). */
public class GoodsCleanupScheduler {

    private final GoodsWebUseCase goodsWebUseCase;

    public GoodsCleanupScheduler(GoodsWebUseCase goodsWebUseCase) {
        this.goodsWebUseCase = goodsWebUseCase;
    }

    public void cleanUp() {
        goodsWebUseCase.getWrapped(0);
    }
}
