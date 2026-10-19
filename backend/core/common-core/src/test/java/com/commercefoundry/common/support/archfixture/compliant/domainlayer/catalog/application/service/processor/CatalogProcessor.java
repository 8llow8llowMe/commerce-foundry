package com.commercefoundry.common.support.archfixture.compliant.domainlayer.catalog.application.service.processor;

import com.commercefoundry.common.support.archfixture.compliant.domainlayer.catalog.application.exception.CatalogException;
import com.commercefoundry.common.support.archfixture.compliant.domainlayer.catalog.domain.model.Catalog;

/** 다른 컨텍스트의 Facade 가 주입하는 Processor. */
public class CatalogProcessor {

    public Catalog visibleCatalog(long catalogId) {
        if (catalogId < 0) {
            throw new CatalogException("catalog unavailable");
        }
        return new Catalog(catalogId, "catalog");
    }
}
