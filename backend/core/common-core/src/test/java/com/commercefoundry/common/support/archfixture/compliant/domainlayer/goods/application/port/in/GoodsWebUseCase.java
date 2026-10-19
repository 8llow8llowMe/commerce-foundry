package com.commercefoundry.common.support.archfixture.compliant.domainlayer.goods.application.port.in;

import com.commercefoundry.common.support.archfixture.compliant.domainlayer.goods.adapter.in.web.dto.request.GoodsSearchRequest;
import com.commercefoundry.common.support.archfixture.compliant.domainlayer.goods.adapter.in.web.dto.response.GoodsResponse;
import com.commercefoundry.common.support.archfixture.compliant.domainlayer.goods.application.model.GoodsSortType;

/** 문서화된 예외 ① — UseCase 의 인자 · 반환은 진입 DTO 다. 입력 타입(application/model)도 받는다. */
public interface GoodsWebUseCase {

    GoodsResponse getGoods(long goodsId, GoodsSortType sortType, GoodsSearchRequest request);
}
