package com.commercefoundry.security.support.archfixture.violating.config;

import feign.Capability;
import org.springframework.context.annotation.Configuration;

/** 위반 예시 — {@code @Configuration}(메타로 {@code @Component})인 Capability. 스캔되면 전역 Capability 가 된다. */
@Configuration
public class ScannedCapability implements Capability {
}
