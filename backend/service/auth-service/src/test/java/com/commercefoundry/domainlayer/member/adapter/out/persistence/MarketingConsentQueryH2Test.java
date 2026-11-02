package com.commercefoundry.domainlayer.member.adapter.out.persistence;

import com.commercefoundry.domainlayer.member.adapter.in.internal.presenter.MarketingConsentInternalPresenter;
import com.commercefoundry.domainlayer.member.application.service.MarketingConsentInternalFacade;
import com.commercefoundry.domainlayer.member.application.service.processor.MarketingConsentQueryProcessor;
import com.commercefoundry.global.config.AuthServiceFeaturesConfig;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:marketing-consent;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS auth",
    "spring.jpa.properties.hibernate.generate_statistics=true"
})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AuthServiceFeaturesConfig.class, QuerydslConfigurer.class, MarketingConsentQueryAdapter.class,
    MarketingConsentQueryProcessor.class, MarketingConsentInternalPresenter.class, MarketingConsentInternalFacade.class})
class MarketingConsentQueryH2Test extends MarketingConsentQueryContract {
}
