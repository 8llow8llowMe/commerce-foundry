package com.commercefoundry.common.support.archfixture.bad.domainlayer.catalog.application.service.processor;

import com.commercefoundry.common.support.archfixture.bad.domainlayer.catalog.domain.model.Catalog;

public class CatalogProcessor {

    public Catalog visibleCatalog(long catalogId) {
        return new Catalog(catalogId, "catalog");
    }

    /** H8 — Processor 의 중첩 타입은 다른 컨텍스트가 쓰지 않는다(돌려주는 것은 domain model 이다). */
    public record Snapshot(String name) {
    }
}
