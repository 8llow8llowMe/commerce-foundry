package com.commercefoundry.common.support.archfixture.ok.domainlayer.goods.adapter.in.webhook;

import com.commercefoundry.common.support.archfixture.ok.domainlayer.goods.adapter.in.support.GoodsLabelSupport;
import com.commercefoundry.common.support.archfixture.ok.domainlayer.goods.adapter.in.webhook.dto.GoodsWebhookPayload;
import com.commercefoundry.common.support.archfixture.ok.domainlayer.goods.application.port.in.GoodsWebUseCase;

/** 웹훅 진입은 자기 DTO · support · UseCase 만 쓴다(H6 · H10 허용). */
public class GoodsWebhookReceiver {

    private final GoodsWebUseCase goodsWebUseCase;

    public GoodsWebhookReceiver(GoodsWebUseCase goodsWebUseCase) {
        this.goodsWebUseCase = goodsWebUseCase;
    }

    public String receive(GoodsWebhookPayload payload) {
        goodsWebUseCase.getWrapped(Long.parseLong(payload.goodsId()));
        return GoodsLabelSupport.label(payload.event());
    }
}
