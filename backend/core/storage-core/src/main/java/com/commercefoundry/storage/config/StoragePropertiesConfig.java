package com.commercefoundry.storage.config;

import com.commercefoundry.storage.properties.StorageProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 스토리지를 쓰는 서비스의 {@code *PropertiesConfig} 가 {@code @Import} 한다. 클라이언트 빈은 {@link StorageConfigurer}({@code *BeansConfig})가 만든다
 * — Snowflake · JWT 발급기와 같은 분리다 ({@code bootstrap-conventions.md}).
 */
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StoragePropertiesConfig {

}
