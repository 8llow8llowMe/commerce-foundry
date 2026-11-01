package com.commercefoundry.global.config;

import com.commercefoundry.common.config.SwaggerPropertiesConfig;
import com.commercefoundry.global.properties.ProductDraftProperties;
import com.commercefoundry.global.properties.StockProperties;
import com.commercefoundry.global.properties.StoreMediaProperties;
import com.commercefoundry.global.properties.StoreProperties;
import com.commercefoundry.messaging.config.MessagingPropertiesConfig;
import com.commercefoundry.persistence.config.SnowflakePropertiesConfig;
import com.commercefoundry.security.internal.InternalTokenPropertiesConfig;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import com.commercefoundry.storage.config.StoragePropertiesConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import({
    InternalTokenPropertiesConfig.class,
    JwtVerificationPropertiesConfig.class,
    MessagingPropertiesConfig.class,
    SnowflakePropertiesConfig.class,
    StoragePropertiesConfig.class,
    SwaggerPropertiesConfig.class
})
@EnableConfigurationProperties({ProductDraftProperties.class, StockProperties.class, StoreProperties.class, StoreMediaProperties.class})
public class ProductServicePropertiesConfig {

}
