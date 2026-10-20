package com.commercefoundry.common.support.archfixture.ok.domainlayer.goods.application.service;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.common.support.archfixture.ok.domainlayer.catalog.application.service.processor.CatalogProcessor;
import com.commercefoundry.common.support.archfixture.ok.domainlayer.catalog.domain.model.Catalog;
import com.commercefoundry.common.support.archfixture.ok.domainlayer.goods.adapter.in.web.dto.request.GoodsSearchRequest;
import com.commercefoundry.common.support.archfixture.ok.domainlayer.goods.adapter.in.web.dto.response.GoodsResponse;
import com.commercefoundry.common.support.archfixture.ok.domainlayer.goods.adapter.in.web.presenter.GoodsWebPresenter;
import com.commercefoundry.common.support.archfixture.ok.domainlayer.goods.application.model.GoodsSortType;
import com.commercefoundry.common.support.archfixture.ok.domainlayer.goods.application.port.in.GoodsWebUseCase;
import com.commercefoundry.common.support.archfixture.ok.domainlayer.goods.application.service.processor.GoodsProcessor;

/**
 * 문서화된 예외 ② — Facade 는 Presenter · 진입 DTO 를 쓴다. 다른 컨텍스트는 Processor 를 주입하고 domain model 을 자기 Processor 에 넘긴다.
 * out-port 는 쓰지 않는다(H11). 응답 봉투 Response 는 쓸 수 있다(H12 예외).
 */
public class GoodsWebFacade implements GoodsWebUseCase {

    private final GoodsProcessor goodsProcessor;
    private final CatalogProcessor catalogProcessor;
    private final GoodsWebPresenter goodsWebPresenter;

    public GoodsWebFacade(GoodsProcessor goodsProcessor, CatalogProcessor catalogProcessor, GoodsWebPresenter goodsWebPresenter) {
        this.goodsProcessor = goodsProcessor;
        this.catalogProcessor = catalogProcessor;
        this.goodsWebPresenter = goodsWebPresenter;
    }

    @Override
    public GoodsResponse getGoods(long goodsId, GoodsSortType sortType, GoodsSearchRequest request) {
        Catalog catalog = catalogProcessor.visibleCatalog(goodsId);
        return new Assembled(goodsWebPresenter.toResponse(goodsProcessor.goodsOf(goodsId, catalog))).response();
    }

    @Override
    public Response<GoodsResponse> getWrapped(long goodsId) {
        return Response.success(getGoods(goodsId, GoodsSortType.NEWEST, null));
    }

    /** Facade 의 중첩 타입도 Facade 로 본다(바깥 클래스 이름). */
    private record Assembled(GoodsResponse response) {
    }
}
