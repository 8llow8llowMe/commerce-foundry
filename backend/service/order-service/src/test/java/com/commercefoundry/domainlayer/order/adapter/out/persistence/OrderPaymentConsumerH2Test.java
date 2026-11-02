package com.commercefoundry.domainlayer.order.adapter.out.persistence;

import static org.mockito.Mockito.mock;

import com.commercefoundry.domainlayer.order.adapter.in.event.PaymentApprovedEventListener;
import com.commercefoundry.domainlayer.order.adapter.out.event.OrderEventAdapter;
import com.commercefoundry.domainlayer.order.application.mapper.OrderMapperImpl;
import com.commercefoundry.domainlayer.order.application.mapper.OrderPaymentMapperImpl;
import com.commercefoundry.domainlayer.order.application.port.out.PaymentQueryPort;
import com.commercefoundry.domainlayer.order.application.port.out.StockReservationPort;
import com.commercefoundry.domainlayer.order.application.port.in.OrderPaymentEventUseCase;
import com.commercefoundry.domainlayer.order.application.service.OrderPaymentEventFacade;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderPaymentProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderStockProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.PaymentApprovalProcessor;
import com.commercefoundry.domainlayer.order.support.OrderPaymentConsumerContract;
import com.commercefoundry.global.config.OrderServiceFeaturesConfig;
import com.commercefoundry.global.messaging.JpaOutboxStore;
import com.commercefoundry.global.messaging.JpaProcessedEventStore;
import com.commercefoundry.messaging.event.EventEnvelopeCodec;
import com.commercefoundry.messaging.event.TopicNames;
import com.commercefoundry.messaging.publish.EventPublisher;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:order-consumer;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS orders",
    "spring.jpa.properties.hibernate.default_schema=orders", "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false", "commerce.messaging.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(OrderPaymentConsumerH2Test.Config.class)
public class OrderPaymentConsumerH2Test extends OrderPaymentConsumerContract {

    private static final EmbeddedKafkaBroker BROKER = new EmbeddedKafkaKraftBroker(1, 1)
        .brokerProperties(java.util.Map.of("offsets.topic.num.partitions", "1", "offsets.topic.replication.factor", "1",
            "group.initial.rebalance.delay.ms", "0"));

    @BeforeAll static void startBroker() { BROKER.afterPropertiesSet(); }
    @AfterAll static void stopBroker() { BROKER.destroy(); }
    @Override protected String bootstrapServers() { return BROKER.getBrokersAsString(); }

    @TestConfiguration
    @Import({OrderServiceFeaturesConfig.class, OrderRepositoryAdapter.class, OrderMapperImpl.class, OrderPaymentRepositoryAdapter.class,
        OrderPaymentMapperImpl.class, JpaProcessedEventStore.class, JpaOutboxStore.class, OrderEventAdapter.class, OrderPaymentProcessor.class,
        PaymentApprovalProcessor.class, OrderStockProcessor.class, OrderPaymentEventFacade.class})
    public static class Config {
        @Bean Clock clock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
        @Bean SnowflakeIdGenerator snowflakeIdGenerator() { return new SnowflakeIdGenerator(4, 4); }
        @Bean TopicNames topicNames() { return new TopicNames("commerce-foundry.test", "order"); }
        @Bean PaymentQueryPort paymentQueryPort() { return mock(PaymentQueryPort.class); }
        @Bean StockReservationPort stockReservationPort() { return mock(StockReservationPort.class); }
        @Bean PaymentApprovedEventListener paymentApprovedEventListener(OrderPaymentEventUseCase useCase) {
            // 업무 UseCase는 실제 Spring 프록시다. 외부 payment/product 포트만 mock으로 둔다.
            return new PaymentApprovedEventListener(new EventEnvelopeCodec(65536), useCase);
        }
        @Bean EventPublisher eventPublisher(JpaOutboxStore store, Clock clock) {
            return new EventPublisher(() -> store, new EventEnvelopeCodec(65536), "order", clock);
        }
    }
}
