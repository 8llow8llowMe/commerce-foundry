package com.commercefoundry.global.config;

import com.commercefoundry.security.internal.InternalTokenIssuerConfigurer;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Keep Feign scanning out of Application/FeaturesConfig so MVC and JPA slices remain isolated. */
@Configuration
@Import(InternalTokenIssuerConfigurer.class)
@EnableFeignClients(basePackages = "com.commercefoundry.domainlayer")
public class ProductServiceFeignConfig {
}
