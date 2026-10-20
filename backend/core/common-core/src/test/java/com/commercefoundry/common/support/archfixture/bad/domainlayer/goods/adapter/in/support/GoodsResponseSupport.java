package com.commercefoundry.common.support.archfixture.bad.domainlayer.goods.adapter.in.support;

import com.commercefoundry.common.support.archfixture.bad.domainlayer.goods.adapter.in.web.dto.response.GoodsResponse;

/** H6 — support 가 진입 DTO(web 응답)를 만든다. support 는 공용 타입만 돌려준다. */
public final class GoodsResponseSupport {

    private GoodsResponseSupport() {
    }

    public static GoodsResponse responseOf(long goodsId) {
        return new GoodsResponse(String.valueOf(goodsId), "support");
    }
}
