package com.commercefoundry.domainlayer.productdraft.application.service;

import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

@DataJpaTest(properties = {
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.jpa.properties.hibernate.hbm2ddl.create_namespaces=true"
})
class ProductDraftRefreshH2Test extends ProductDraftRefreshPersistenceTestSupport {
}
