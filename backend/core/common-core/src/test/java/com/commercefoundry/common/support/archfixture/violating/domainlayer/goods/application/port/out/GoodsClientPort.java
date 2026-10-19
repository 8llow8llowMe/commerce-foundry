package com.commercefoundry.common.support.archfixture.violating.domainlayer.goods.application.port.out;

import com.commercefoundry.common.support.archfixture.violating.domainlayer.goods.adapter.out.client.feign.dto.GoodsFeignResponse;

/** H2 — out-adapter 타입(Feign 응답)이 application 으로 샌다. */
public interface GoodsClientPort {

    GoodsFeignResponse fetchGoods(long goodsId);
}
