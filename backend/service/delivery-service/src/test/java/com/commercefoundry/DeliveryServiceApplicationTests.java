package com.commercefoundry;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.querydsl.jpa.impl.JPAQueryFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * 컨텍스트 로딩 게이트. test 프로파일(H2, Eureka 꺼짐)에서 빈 정의가 끝까지 조립되는지 본다.
 */
@SpringBootTest
@ActiveProfiles("test")
class DeliveryServiceApplicationTests {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    @DisplayName("컨텍스트가 뜨고 global.config 가 import 한 공통 빈이 조립된다")
    void contextLoads() {
        assertThat(applicationContext.getBeanNamesForType(SnowflakeIdGenerator.class)).hasSize(1);
        assertThat(applicationContext.getBeanNamesForType(JPAQueryFactory.class)).hasSize(1);
        assertThat(applicationContext.containsBean("jpaAuditingHandler")).isTrue();
    }

    @Test
    @DisplayName("Swagger 문서가 고객 / 어드민 그룹으로 나뉜다")
    void swaggerGroups() {
        assertThat(applicationContext.getBeansOfType(GroupedOpenApi.class).values())
            .extracting(GroupedOpenApi::getGroup)
            .containsExactlyInAnyOrder("customer", "admin");
    }
}
