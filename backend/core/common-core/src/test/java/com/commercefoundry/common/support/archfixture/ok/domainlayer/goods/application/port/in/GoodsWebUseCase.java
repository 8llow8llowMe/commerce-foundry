package com.commercefoundry.common.support.archfixture.ok.domainlayer.goods.application.port.in;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.common.support.archfixture.ok.domainlayer.goods.adapter.in.web.dto.request.GoodsSearchRequest;
import com.commercefoundry.common.support.archfixture.ok.domainlayer.goods.adapter.in.web.dto.response.GoodsResponse;
import com.commercefoundry.common.support.archfixture.ok.domainlayer.goods.application.model.GoodsSortType;

/** 문서화된 예외 ① — UseCase 의 인자 · 반환은 진입 DTO 다. 입력 타입(application/model)도 받고, 응답 봉투 Response 를 반환할 수 있다(H12 예외). */
public interface GoodsWebUseCase {

    GoodsResponse getGoods(long goodsId, GoodsSortType sortType, GoodsSearchRequest request);

    Response<GoodsResponse> getWrapped(long goodsId);
}
