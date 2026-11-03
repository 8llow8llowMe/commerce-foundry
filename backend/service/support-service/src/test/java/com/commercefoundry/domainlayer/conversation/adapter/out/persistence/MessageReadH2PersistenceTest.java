package com.commercefoundry.domainlayer.conversation.adapter.out.persistence;

import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
    "spring.datasource.type=com.zaxxer.hikari.HikariDataSource",
    "spring.datasource.url=jdbc:h2:mem:message-read-contract;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS support",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.properties.hibernate.generate_statistics=true",
    "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.properties.hibernate.default_schema=support",
    "spring.jpa.properties.hibernate.hbm2ddl.create_namespaces=true",
    "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false"
})
class MessageReadH2PersistenceTest extends MessageReadPersistenceContract {
}
