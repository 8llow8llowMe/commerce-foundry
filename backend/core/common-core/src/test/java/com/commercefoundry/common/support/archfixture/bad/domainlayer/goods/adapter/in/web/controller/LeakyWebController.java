package com.commercefoundry.common.support.archfixture.bad.domainlayer.goods.adapter.in.web.controller;

import com.commercefoundry.common.support.archfixture.bad.domainlayer.goods.adapter.in.web.presenter.GoodsWebPresenter;
import com.commercefoundry.common.support.archfixture.bad.domainlayer.goods.adapter.out.persistence.repository.GoodsJpaRepository;
import com.commercefoundry.common.support.archfixture.bad.domainlayer.goods.application.exception.GoodsErrorCode;
import com.commercefoundry.common.support.archfixture.bad.domainlayer.goods.application.info.GoodsInfo;
import com.commercefoundry.common.support.archfixture.bad.domainlayer.goods.application.service.processor.GoodsProcessor;
import com.commercefoundry.common.support.archfixture.bad.domainlayer.goods.domain.model.Goods;

/**
 * H1 — 컨트롤러가 Processor · Presenter · Repository · Info · domain · *ValidationMessage 가 아닌 예외 타입을 직접 쓴다.
 * H7 — in-adapter 가 Repository 를 쓴다. H10 — 진입이 Processor 를 쓴다.
 */
public class LeakyWebController {

    private final GoodsProcessor goodsProcessor;
    private final GoodsWebPresenter goodsWebPresenter;
    private final GoodsJpaRepository goodsJpaRepository;

    public LeakyWebController(GoodsProcessor goodsProcessor, GoodsWebPresenter goodsWebPresenter, GoodsJpaRepository goodsJpaRepository) {
        this.goodsProcessor = goodsProcessor;
        this.goodsWebPresenter = goodsWebPresenter;
        this.goodsJpaRepository = goodsJpaRepository;
    }

    public Object getGoods(long goodsId) {
        goodsJpaRepository.findById(goodsId);
        return goodsProcessor.goodsOf(goodsId);
    }

    public String describe(GoodsInfo info) {
        return info.name();
    }

    public String nameOf(Goods goods) {
        return goods.name();
    }

    public GoodsErrorCode notFound() {
        return GoodsErrorCode.GOODS_NOT_FOUND;
    }
}
