package com.commercefoundry.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * JPA Auditing 은 {@code BaseEntity} 의 생성·수정 시각을 채운다. 스케줄링은 관리자 감사 로그 보관 정리({@code AdminAuditLogRetentionScheduler})가 쓴다.
 */
@Configuration
@EnableJpaAuditing
@EnableScheduling
public class AuthServiceFeaturesConfig {

}
