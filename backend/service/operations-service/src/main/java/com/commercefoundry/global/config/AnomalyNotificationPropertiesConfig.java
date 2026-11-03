package com.commercefoundry.global.config;

import com.commercefoundry.global.properties.AnomalyNotificationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AnomalyNotificationProperties.class)
public class AnomalyNotificationPropertiesConfig {
}
