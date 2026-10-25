package com.commercefoundry.messaging.config;

import com.commercefoundry.messaging.properties.MessagingProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 메시징을 쓰는 서비스의 {@code *PropertiesConfig} 가 {@code @Import} 한다. 빈은 {@link MessagingConfigurer}({@code *BeansConfig})가 만든다
 * — Storage · Snowflake 와 같은 분리다 ({@code bootstrap-conventions.md}).
 */
@Configuration
@EnableConfigurationProperties(MessagingProperties.class)
public class MessagingPropertiesConfig {

}
