package com.commercefoundry.domainlayer.order.adapter.out.persistence;

import static com.commercefoundry.domainlayer.order.support.OrderFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.order.application.mapper.OrderMapperImpl;
import com.commercefoundry.domainlayer.order.domain.model.Order;
import com.commercefoundry.domainlayer.order.domain.model.OrderCustomOption;
import com.commercefoundry.domainlayer.order.domain.model.OrderCustomOptions;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import com.commercefoundry.global.config.OrderServiceFeaturesConfig;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Import({OrderServiceFeaturesConfig.class, QuerydslConfigurer.class, OrderPickingReadAdapter.class})
class OrderPickingReadAdapterTest {
    @Autowired private TestEntityManager entityManager;
    @Autowired private OrderPickingReadAdapter adapter;

    @Test
    void projectsHeadersCountryAndAllLinesInOneQueryWithoutLoadingPersonalEntities() {
        var mapper = new OrderMapperImpl();
        var ids = new AtomicLong(100);
        var options = OrderCustomOptions.of(List.of(new OrderCustomOption("test-field", "test-value")));
        Order order = Order.place(1, 7, domesticQuote(quoteLine(10, 100, 2), quoteLine(10, 100, 3, options)),
            ids::incrementAndGet, address("KR"), 1000, Instant.parse("2026-11-14T00:00:00Z"));
        entityManager.persist(mapper.toEntity(order));
        entityManager.persist(mapper.toShippingAddressEntity(order));
        mapper.toItemEntities(order).forEach(entityManager::persist);
        entityManager.flush();
        entityManager.clear();
        var statistics = entityManager.getEntityManager().getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        var results = adapter.findAll(List.of(1L, 999L));
        assertThat(results).hasSize(1);
        var snapshot = results.getFirst();
        assertThat(snapshot.orderId()).isEqualTo(1);
        assertThat(snapshot.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(snapshot.countryCode()).isEqualTo("KR");
        assertThat(snapshot.lines()).hasSize(2);
        assertThat(snapshot.lines()).extracting(line -> line.productId()).containsOnly(7010L);
        assertThat(snapshot.lines()).extracting(line -> line.quantity()).containsExactly(2L, 3L);
        assertThat(snapshot.lines().getLast().customOrder()).isTrue();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        assertThat(statistics.getEntityLoadCount()).isZero();
        assertThat(adapter.findAll(List.of())).isEmpty();
    }
}
