package com.commercefoundry.common.support.archfixture.violating.domainlayer.goods.application.service;

import com.commercefoundry.common.support.archfixture.violating.domainlayer.catalog.application.port.out.CatalogReaderPort;
import com.commercefoundry.common.support.archfixture.violating.domainlayer.goods.adapter.out.persistence.repository.GoodsJpaRepository;

/** H2 — Facade 라도 out-adapter(Repository)는 쓰지 않는다. H8 — 다른 컨텍스트의 out-port 를 직접 부른다. */
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
}
