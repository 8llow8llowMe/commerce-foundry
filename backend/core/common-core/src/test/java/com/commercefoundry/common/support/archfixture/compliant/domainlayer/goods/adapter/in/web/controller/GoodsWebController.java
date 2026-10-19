package com.commercefoundry.common.support.archfixture.compliant.domainlayer.goods.adapter.in.web.controller;

import com.commercefoundry.common.support.archfixture.compliant.domainlayer.goods.adapter.in.web.dto.request.GoodsSearchRequest;
import com.commercefoundry.common.support.archfixture.compliant.domainlayer.goods.adapter.in.web.dto.response.GoodsResponse;
import com.commercefoundry.common.support.archfixture.compliant.domainlayer.goods.application.model.GoodsSortType;
import com.commercefoundry.common.support.archfixture.compliant.domainlayer.goods.application.port.in.GoodsWebUseCase;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GoodsWebController {

    private final GoodsWebUseCase goodsWebUseCase;

    public GoodsWebController(GoodsWebUseCase goodsWebUseCase) {
        this.goodsWebUseCase = goodsWebUseCase;
    }

    public GoodsResponse getGoods(long goodsId, GoodsSortType sortType, GoodsSearchRequest request) {
        return goodsWebUseCase.getGoods(goodsId, sortType, request);
    }
}
