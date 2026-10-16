package com.commercefoundry.global.config;

import com.commercefoundry.common.config.SwaggerPropertiesConfig;
import com.commercefoundry.persistence.config.SnowflakePropertiesConfig;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import({
    JwtVerificationPropertiesConfig.class,
    SnowflakePropertiesConfig.class,
    SwaggerPropertiesConfig.class
})
public class AuthServicePropertiesConfig {

}
