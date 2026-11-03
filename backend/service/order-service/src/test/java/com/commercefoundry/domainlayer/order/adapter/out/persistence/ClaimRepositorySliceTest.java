package com.commercefoundry.domainlayer.order.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.order.adapter.out.persistence.repository.ClaimRepository;
import com.querydsl.jpa.impl.JPAQueryFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/** 관리자 조회를 추가해도 일반 접수 저장소의 JPA 슬라이스는 QueryDSL 빈 없이 동작한다. */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:claim-slice;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;INIT=CREATE SCHEMA IF NOT EXISTS orders")
class ClaimRepositorySliceTest {
    @Autowired ClaimRepository claims;
    @Autowired ApplicationContext context;

    @Test
    void plainRepositoryDoesNotRequireQuerydslConfiguration() {
        assertThat(context.getBeanNamesForType(JPAQueryFactory.class)).isEmpty();
        assertThat(claims.findById(1L)).isEmpty();
    }
}
