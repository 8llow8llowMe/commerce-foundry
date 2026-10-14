package com.commercefoundry.persistence.config;

import com.commercefoundry.persistence.properties.SnowflakeProperties;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import org.springframework.context.annotation.Bean;

public class SnowflakeConfigurer {

    @Bean
    public SnowflakeIdGenerator snowflakeIdGenerator(SnowflakeProperties properties) {
        return new SnowflakeIdGenerator(properties.datacenterId(), properties.workerId());
    }
}
