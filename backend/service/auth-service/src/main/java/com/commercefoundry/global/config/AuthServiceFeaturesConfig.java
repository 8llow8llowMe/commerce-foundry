package com.commercefoundry.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * JPA Auditing 은 {@code BaseEntity} 의 생성·수정 시각을 채운다.
 */
@Configuration
@EnableJpaAuditing
public class AuthServiceFeaturesConfig {

}
