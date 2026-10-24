package com.commercefoundry.ai.config;

import com.commercefoundry.ai.properties.AiProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * AI 를 쓰는 서비스의 {@code *PropertiesConfig} 가 {@code @Import} 한다. 클라이언트 빈은 {@link AiConfigurer}({@code *BeansConfig})가 만든다
 * — Storage 와 같은 분리다 ({@code bootstrap-conventions.md} AI).
 */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiPropertiesConfig {

}
