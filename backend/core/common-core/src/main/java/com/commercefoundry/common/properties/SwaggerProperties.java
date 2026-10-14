package com.commercefoundry.common.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param serverUrl Swagger UI 가 "Try it out" 요청을 보낼 주소. 게이트웨이 경유 주소를 넣는다
 */
@ConfigurationProperties(prefix = "swagger")
public record SwaggerProperties(
    String serverUrl
) {

}
