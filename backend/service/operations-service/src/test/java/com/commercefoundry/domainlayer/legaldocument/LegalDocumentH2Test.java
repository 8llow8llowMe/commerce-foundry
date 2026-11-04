package com.commercefoundry.domainlayer.legaldocument;

import com.commercefoundry.domainlayer.legaldocument.adapter.out.persistence.LegalDocumentRepositoryAdapter;
import com.commercefoundry.domainlayer.legaldocument.adapter.out.persistence.entity.LegalDocumentEntity;
import com.commercefoundry.domainlayer.legaldocument.adapter.out.persistence.repository.LegalDocumentRepository;
import com.commercefoundry.global.config.OperationsServiceFeaturesConfig;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest
@EntityScan(basePackageClasses = LegalDocumentEntity.class)
@EnableJpaRepositories(basePackageClasses = LegalDocumentRepository.class)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {"spring.datasource.url=jdbc:h2:mem:legal-document-persistence;MODE=PostgreSQL;"
    + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "spring.jpa.hibernate.ddl-auto=validate",
    "spring.sql.init.mode=always", "spring.sql.init.schema-locations=classpath:ddl/operations-schema.sql",
    "spring.jpa.defer-datasource-initialization=false"})
@Import({OperationsServiceFeaturesConfig.class, LegalDocumentRepositoryAdapter.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class LegalDocumentH2Test extends LegalDocumentPersistenceContract {
}
