package com.commercefoundry.persistence.config;

import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import org.springframework.context.annotation.Bean;

/**
 * 서비스의 {@code *BeansConfig} 가 {@code @Import} 한다 ({@code bootstrap-conventions.md}).
 * 컴포넌트 스캔에 걸리지 않게 {@code @Configuration} 을 달지 않는다 — 어떤 공통 설정을 쓰는지 서비스에서 보이게 한다.
 */
public class QuerydslConfigurer {

    @Bean
    public JPAQueryFactory jpaQueryFactory(EntityManager entityManager) {
        return new JPAQueryFactory(entityManager);
    }
}
