package com.commercefoundry;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.payment.adapter.out.persistence.repository.PaymentWebhookInboxRepository;
import com.commercefoundry.global.config.PaymentServiceFeaturesConfig;
import com.querydsl.jpa.impl.JPAQueryFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/** outbox 같은 무관한 JPA slice는 웹훅 CAS 전용 QueryDSL 빈을 제공할 필요가 없다. */
@DataJpaTest(properties = {
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.jpa.properties.hibernate.hbm2ddl.create_namespaces=true"
})
@ActiveProfiles("test")
@Import(PaymentServiceFeaturesConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentWebhookRepositorySliceTest {
    @Autowired private ApplicationContext context;
    @Autowired private PaymentWebhookInboxRepository inbox;

    @Test
    void unrelatedJpaSliceDoesNotRequireWebhookQueryFactory() {
        assertThat(context.getBeansOfType(JPAQueryFactory.class)).isEmpty();
        assertThat(inbox.count()).isZero();
    }
}
