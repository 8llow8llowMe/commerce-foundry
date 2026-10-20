package com.commercefoundry.common.support.archfixture.bad.domainlayer.goods.adapter.in.webhook;

import com.commercefoundry.common.support.archfixture.bad.domainlayer.goods.adapter.in.web.dto.response.GoodsResponse;

/** H6 — 웹훅 진입이 고객 응답 DTO 를 쓴다. */
public class GoodsWebhookReceiver {

    public String receive(GoodsResponse response) {
        return response.id();
    }
}
