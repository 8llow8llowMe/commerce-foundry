package com.commercefoundry.global.config;

import com.commercefoundry.common.config.SwaggerPropertiesConfig;
import com.commercefoundry.global.properties.AgreementProperties;
import com.commercefoundry.global.properties.AuthLoginGuardProperties;
import com.commercefoundry.global.properties.AuthTokenProperties;
import com.commercefoundry.global.properties.ShippingAddressProperties;
import com.commercefoundry.persistence.config.SnowflakePropertiesConfig;
import com.commercefoundry.security.internal.InternalTokenPropertiesConfig;
import com.commercefoundry.security.issuer.JwtIssuerPropertiesConfig;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import({
    InternalTokenPropertiesConfig.class,
    JwtIssuerPropertiesConfig.class,
    JwtVerificationPropertiesConfig.class,
    SnowflakePropertiesConfig.class,
    SwaggerPropertiesConfig.class
})
@EnableConfigurationProperties({AgreementProperties.class, AuthLoginGuardProperties.class, AuthTokenProperties.class, ShippingAddressProperties.class})
public class AuthServicePropertiesConfig {

}
