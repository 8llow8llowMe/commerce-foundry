package com.commercefoundry.common.support.archfixture.bad.domainlayer.goods.adapter.in.admin.presenter;

import com.commercefoundry.common.support.archfixture.bad.domainlayer.goods.adapter.in.web.dto.response.GoodsResponse;

/** H6 — 어드민 진입이 고객 응답 DTO 를 같이 쓴다. */
public class GoodsAdminPresenter {

    public GoodsResponse toResponse(long goodsId) {
        return new GoodsResponse(String.valueOf(goodsId), "admin");
    }
}
