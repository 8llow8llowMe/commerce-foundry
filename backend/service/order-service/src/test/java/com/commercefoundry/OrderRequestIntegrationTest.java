package com.commercefoundry;

import com.commercefoundry.domainlayer.order.support.OrderRequestPersistenceContract;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class OrderRequestIntegrationTest extends OrderRequestPersistenceContract {
    @Override protected boolean postgres() { return false; }
}
