package com.commercefoundry.persistence.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 인스턴스마다 {@code (datacenterId, workerId)} 쌍이 달라야 한다. 같으면 같은 밀리초에 같은 ID 가 나온다.
 *
 * @param datacenterId 0~31
 * @param workerId     0~31
 */
@ConfigurationProperties(prefix = "snowflake")
public record SnowflakeProperties(
    long datacenterId,
    long workerId
) {

}
