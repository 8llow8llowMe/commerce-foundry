package com.commercefoundry.common.support.archfixture.bad.domainlayer.goods.application.service;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.common.support.archfixture.bad.domainlayer.catalog.application.port.out.CatalogReaderPort;
import com.commercefoundry.common.support.archfixture.bad.domainlayer.catalog.application.service.processor.CatalogProcessor;
import com.commercefoundry.common.support.archfixture.bad.domainlayer.catalog.application.service.processor.CatalogSkuCache;
import com.commercefoundry.common.support.archfixture.bad.domainlayer.goods.adapter.out.persistence.repository.GoodsJpaRepository;
import com.commercefoundry.common.support.archfixture.bad.domainlayer.goods.application.port.out.query.GoodsQueryResult;

/**
 * H2 — Facade 라도 out-adapter(Repository)는 쓰지 않는다. H8 — 다른 컨텍스트의 out-port · Processor 의 중첩 타입 · processor 패키지의 *Processor 아닌 클래스를 쓴다.
 * H11 — Facade 가 out-port · QueryResult 를 쓴다. 응답 봉투 Response 는 Facade 에서 허용(H12 에 걸리지 않는다).
 */
public class GoodsWebFacade {

    private final GoodsJpaRepository goodsJpaRepository;
    private final CatalogReaderPort catalogReaderPort;

    public GoodsWebFacade(GoodsJpaRepository goodsJpaRepository, CatalogReaderPort catalogReaderPort) {
        this.goodsJpaRepository = goodsJpaRepository;
        this.catalogReaderPort = catalogReaderPort;
    }

    public Object getGoods(long goodsId) {
        catalogReaderPort.findCatalog(goodsId);
        return goodsJpaRepository.findById(goodsId);
    }

    public String summaryOf(GoodsQueryResult result) {
        return result.name();
    }

    public String snapshotName(CatalogProcessor.Snapshot snapshot) {
        return snapshot.name();
    }

    public String cached(CatalogSkuCache cache) {
        return cache.lookup(0);
    }

    public Response<String> wrapped() {
        return Response.success("goods");
    }
}
