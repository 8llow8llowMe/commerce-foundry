package com.commercefoundry.common.support.archfixture.violating.domainlayer.goods.application.service.processor;

import com.commercefoundry.common.support.archfixture.violating.domainlayer.catalog.application.service.processor.CatalogProcessor;
import com.commercefoundry.common.support.archfixture.violating.domainlayer.goods.adapter.in.web.dto.response.GoodsResponse;
import com.commercefoundry.common.support.archfixture.violating.domainlayer.goods.application.info.GoodsInfo;

/** H2 · H3 — Processor 가 Response DTO 를 만든다. H8 — 다른 컨텍스트 Processor 를 주입한다(Facade 만 주입한다). */
public class GoodsProcessor {

    private final CatalogProcessor catalogProcessor;

    public GoodsProcessor(CatalogProcessor catalogProcessor) {
        this.catalogProcessor = catalogProcessor;
    }

    public GoodsInfo goodsOf(long goodsId) {
        return new GoodsInfo(goodsId, catalogProcessor.visibleCatalog(goodsId).name());
    }

    public GoodsResponse responseOf(GoodsInfo info) {
        return new GoodsResponse(String.valueOf(info.id()), info.name());
    }
}
