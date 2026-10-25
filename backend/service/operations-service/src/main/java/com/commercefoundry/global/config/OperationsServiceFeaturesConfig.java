package com.commercefoundry.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * JPA Auditing 은 {@code BaseEntity} 의 생성·수정 시각을 채운다. 스케줄링은 백그라운드 작업의 lease 만료 판정이 쓴다 (#212 · #307).
 * 작업 실행은 주기 조회가 아니라 Kafka 실행 신호다.
 */
@Configuration
@EnableJpaAuditing
@EnableScheduling
public class OperationsServiceFeaturesConfig {

}
