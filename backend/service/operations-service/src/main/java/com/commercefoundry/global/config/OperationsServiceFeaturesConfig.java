package com.commercefoundry.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * JPA Auditing 은 {@code BaseEntity} 의 생성·수정 시각을 채운다. 스케줄링은 백그라운드 작업 워커의 폴러 · 정기 작업 등록이 쓴다 (#212).
 */
@Configuration
@EnableJpaAuditing
@EnableScheduling
public class OperationsServiceFeaturesConfig {

}
