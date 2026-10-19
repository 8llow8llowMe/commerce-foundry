package com.commercefoundry.common.support.archfixture.violating.domainlayer.catalog.application.service.processor;

import com.commercefoundry.common.support.archfixture.violating.domainlayer.catalog.domain.model.Catalog;

public class CatalogProcessor {

    public Catalog visibleCatalog(long catalogId) {
        return new Catalog(catalogId, "catalog");
    }
}
