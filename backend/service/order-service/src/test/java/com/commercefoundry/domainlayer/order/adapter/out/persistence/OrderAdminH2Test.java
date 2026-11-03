package com.commercefoundry.domainlayer.order.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.commercefoundry.domainlayer.order.adapter.in.admin.presenter.OrderAdminPresenter;
import com.commercefoundry.domainlayer.order.application.command.OrderStatusChangeCommand;
import com.commercefoundry.domainlayer.order.application.mapper.OrderMapperImpl;
import com.commercefoundry.domainlayer.order.application.service.OrderAdminFacade;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderAdminAuditProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderAdminProcessor;
import com.commercefoundry.domainlayer.order.application.service.processor.OrderFulfillmentProcessor;
import com.commercefoundry.domainlayer.order.support.OrderAdminPersistenceContract;
import com.commercefoundry.domainlayer.order.domain.model.OrderStatus;
import com.commercefoundry.global.config.OrderServiceFeaturesConfig;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.commercefoundry.security.audit.AdminAuditLog;
import java.time.Clock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 어드민 주문 관리 영속성 계약을 H2(PostgreSQL 모드)로 돌린다 — Docker 없이 로컬에서 도는 쪽이다. 엔진 의존 동작은 {@code OrderAdminPostgresTest} 가 본다. */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:admin;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS orders",
    "spring.jpa.properties.hibernate.default_schema=orders", "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false", "commerce.messaging.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    OrderServiceFeaturesConfig.class,
    OrderRepositoryAdapter.class,
    OrderMapperImpl.class,
    OrderAdminProcessor.class,
    OrderAdminFacade.class,
    OrderAdminAuditProcessor.class,
    OrderAdminPresenter.class,
    OrderFulfillmentProcessor.class,
    OrderAdminH2Test.Config.class
})
public class OrderAdminH2Test extends OrderAdminPersistenceContract {

    @Autowired private OrderAdminFacade facade;
    @MockitoSpyBean private OrderAdminProcessor auditedProcessor;

    @Test
    @DisplayName("실제 NEVER Facade 와 H2 변경 트랜잭션 — 변경 커밋 뒤 상세 조회만 실패하면 상태와 감사 SUCCESS 한 줄을 유지한다")
    void committedChangeKeepsSuccessWhenResponseReadFails() {
        long orderId = create(OrderStatus.PAID, false);
        IllegalStateException failure = new IllegalStateException("response unavailable");
        doThrow(failure).when(auditedProcessor).getDetail(orderId);
        assertThat(AopUtils.isAopProxy(facade)).isTrue();
        assertThat(AopUtils.isAopProxy(auditedProcessor)).isTrue();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();

        Logger logger = (Logger) LoggerFactory.getLogger(AdminAuditLog.LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThatThrownBy(() -> facade.changeStatus(orderId, new OrderStatusChangeCommand(OrderStatus.PREPARING, "포장 시작"), 77L))
                .isSameAs(failure);

            assertThat(jdbc.queryForObject("select status from orders.orders where id = ?", String.class, orderId)).isEqualTo("PREPARING");
            assertThat(jdbc.queryForObject("select count(*) from orders.order_status_history where order_id = ?", Long.class, orderId)).isEqualTo(2L);
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getFormattedMessage())
                    .isEqualTo("action=ORDER_STATUS_CHANGE actorMemberId=77 target=order:" + orderId + " result=SUCCESS");
                assertThat(event.getLevel()).isEqualTo(Level.INFO);
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @TestConfiguration
    public static class Config {

        @Bean
        SnowflakeIdGenerator snowflakeIdGenerator() {
            return new SnowflakeIdGenerator(2, 3);
        }

        /** 어드민 수동 배송 완료 · 출고 · 배송 완료 반영의 시각 (#18). */
        @Bean
        Clock orderClock() {
            return Clock.systemUTC();
        }
    }
}
