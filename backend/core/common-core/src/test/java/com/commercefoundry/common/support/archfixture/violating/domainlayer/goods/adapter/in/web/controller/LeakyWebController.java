package com.commercefoundry.common.support.archfixture.violating.domainlayer.goods.adapter.in.web.controller;

import com.commercefoundry.common.support.archfixture.violating.domainlayer.goods.adapter.in.web.presenter.GoodsWebPresenter;
import com.commercefoundry.common.support.archfixture.violating.domainlayer.goods.adapter.out.persistence.repository.GoodsJpaRepository;
import com.commercefoundry.common.support.archfixture.violating.domainlayer.goods.application.info.GoodsInfo;
import com.commercefoundry.common.support.archfixture.violating.domainlayer.goods.application.service.processor.GoodsProcessor;

/** H1 — 컨트롤러가 Processor · Presenter · Repository · Info 를 직접 쓴다. H7 — in-adapter 가 Repository 를 쓴다. */
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
}
