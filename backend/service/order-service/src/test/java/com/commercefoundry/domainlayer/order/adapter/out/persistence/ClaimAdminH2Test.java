package com.commercefoundry.domainlayer.order.adapter.out.persistence;

import com.commercefoundry.domainlayer.order.support.ClaimAdminQueryContract;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {"spring.jpa.properties.hibernate.generate_statistics=true", "springdoc.api-docs.enabled=true"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ClaimAdminH2Test extends ClaimAdminQueryContract {
}
