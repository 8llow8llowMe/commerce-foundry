package com.commercefoundry.domainlayer.notification.adapter.out.persistence;

import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@SpringJUnitConfig(PaymentAnomalyPersistenceConfig.class)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:anomaly-state;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS operations",
    "spring.datasource.username=sa", "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.jpa.properties.hibernate.default_schema=operations", "spring.jpa.properties.hibernate.generate_statistics=true",
    "spring.jpa.properties.hibernate.use_sql_comments=false", "spring.data.jpa.repositories.enabled=false"
})
@Execution(ExecutionMode.SAME_THREAD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentAnomalyPersistenceH2Test extends PaymentAnomalyPersistenceContract { }
