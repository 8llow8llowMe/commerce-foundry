package com.commercefoundry.global.config;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.global.media.MediaUrlPolicy;
import com.commercefoundry.global.properties.StoreMediaProperties;
import com.commercefoundry.messaging.config.MessagingConfigurer;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.persistence.config.SnowflakeConfigurer;
import com.commercefoundry.security.resourceserver.InternalResourceServerConfigurer;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import({
    InternalResourceServerConfigurer.class,
    MessagingConfigurer.class,
    QuerydslConfigurer.class,
    ResourceServerSecurityConfigurer.class,
    SnowflakeConfigurer.class,
    SwaggerSecurityConfigurer.class
})
public class ProductServiceBeansConfig {

    /**
     * 업무 시각(재고 선점 만료 · 전시 섹션 노출 기간)의 기준. 테스트가 고정 시계로 바꿔 만료 · 노출을 제어한다.
     * 감사 컬럼(created_at · updated_at)은 이 시계를 쓰지 않는다.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /** 어드민 미디어 주소 허용 판정. 공개 주소가 형식에 맞지 않으면 여기서 기동이 멈춘다 — 비어 있으면 뜨고 미디어 쓰기만 거절한다. */
    @Bean
    public MediaUrlPolicy mediaUrlPolicy(StoreMediaProperties storeMediaProperties) {
        return MediaUrlPolicy.of(storeMediaProperties.publicBaseUrl());
    }
}
