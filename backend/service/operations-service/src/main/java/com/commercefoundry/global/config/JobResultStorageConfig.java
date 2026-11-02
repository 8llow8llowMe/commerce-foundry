package com.commercefoundry.global.config;

import com.commercefoundry.global.properties.JobResultFileProperties;
import com.commercefoundry.storage.config.StorageConfigurer;
import com.commercefoundry.storage.config.StoragePropertiesConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@Import({StorageConfigurer.class, StoragePropertiesConfig.class})
@EnableConfigurationProperties(JobResultFileProperties.class)
public class JobResultStorageConfig {
}
