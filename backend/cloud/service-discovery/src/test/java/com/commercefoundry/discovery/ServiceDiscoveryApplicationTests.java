package com.commercefoundry.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.netflix.eureka.registry.PeerAwareInstanceRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = DiscoverySecurityTest.PASSWORD_PROPERTY)
class ServiceDiscoveryApplicationTests {

    @Autowired
    private PeerAwareInstanceRegistry instanceRegistry;

    @Test
    @DisplayName("Eureka 서버 컨텍스트가 뜨고 인스턴스 레지스트리가 준비된다")
    void contextLoads() {
        assertThat(instanceRegistry).isNotNull();
    }
}
