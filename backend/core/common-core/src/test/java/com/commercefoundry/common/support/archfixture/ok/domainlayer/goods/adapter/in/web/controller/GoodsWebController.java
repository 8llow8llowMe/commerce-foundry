package com.commercefoundry.common.support.archfixture.ok.domainlayer.goods.adapter.in.web.controller;

import com.commercefoundry.common.support.archfixture.ok.domainlayer.goods.adapter.in.web.dto.request.GoodsSearchRequest;
import com.commercefoundry.common.support.archfixture.ok.domainlayer.goods.adapter.in.web.dto.response.GoodsResponse;
import com.commercefoundry.common.support.archfixture.ok.domainlayer.goods.application.exception.GoodsValidationMessage;
import com.commercefoundry.common.support.archfixture.ok.domainlayer.goods.application.model.GoodsSortType;
import com.commercefoundry.common.support.archfixture.ok.domainlayer.goods.application.port.in.GoodsWebUseCase;
import org.springframework.web.bind.annotation.RestController;

/** H1 허용 — UseCase · 입력 타입(model) · *ValidationMessage 를 쓴다. */
@RestController
public class GoodsWebController {

    private final GoodsWebUseCase goodsWebUseCase;

    public GoodsWebController(GoodsWebUseCase goodsWebUseCase) {
        this.goodsWebUseCase = goodsWebUseCase;
    }

    public GoodsResponse getGoods(long goodsId, GoodsSortType sortType, GoodsSearchRequest request) {
        return goodsWebUseCase.getGoods(goodsId, sortType, request);
    }

    public String keywordRequiredMessage() {
        return GoodsValidationMessage.keywordRequired();
    }
}
