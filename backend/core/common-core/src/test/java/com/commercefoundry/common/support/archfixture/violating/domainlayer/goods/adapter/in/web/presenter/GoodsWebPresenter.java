package com.commercefoundry.common.support.archfixture.violating.domainlayer.goods.adapter.in.web.presenter;

import com.commercefoundry.common.support.archfixture.violating.domainlayer.goods.adapter.in.web.dto.response.GoodsResponse;
import com.commercefoundry.common.support.archfixture.violating.domainlayer.goods.application.port.out.query.GoodsQueryResult;
import com.commercefoundry.common.support.archfixture.violating.domainlayer.goods.application.service.processor.GoodsProcessor;

/** H9 — Presenter 가 Processor 를 부르고 QueryResult 를 받는다. */
public class GoodsWebPresenter {

    private final GoodsProcessor goodsProcessor;

    public GoodsWebPresenter(GoodsProcessor goodsProcessor) {
        this.goodsProcessor = goodsProcessor;
    }

    public GoodsResponse toResponse(GoodsQueryResult result) {
        return goodsProcessor.responseOf(goodsProcessor.goodsOf(result.id()));
    }
}
