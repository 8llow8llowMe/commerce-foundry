package com.commercefoundry.global.config;

import com.commercefoundry.common.config.SwaggerPropertiesConfig;
import com.commercefoundry.persistence.config.SnowflakePropertiesConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import({
    SwaggerPropertiesConfig.class,
    SnowflakePropertiesConfig.class
})
public class ProductServicePropertiesConfig {

}
