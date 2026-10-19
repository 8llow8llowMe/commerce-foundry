package com.commercefoundry.common.support.archfixture.violating.domainlayer.catalog.application.port.out;

import com.commercefoundry.common.support.archfixture.violating.domainlayer.catalog.domain.model.Catalog;

public interface CatalogReaderPort {

    Catalog findCatalog(long catalogId);
}
