package com.commercefoundry.domainlayer.notification.application.service;

import com.commercefoundry.domainlayer.notification.adapter.in.scheduler.LowStockAlertScheduler;
import com.commercefoundry.domainlayer.notification.adapter.out.persistence.LowStockAlertStateAdapter;
import com.commercefoundry.domainlayer.notification.adapter.out.persistence.NotificationBatchInsertAdapter;
import com.commercefoundry.domainlayer.notification.adapter.out.persistence.entity.NotificationEntity;
import com.commercefoundry.domainlayer.notification.adapter.out.persistence.repository.LowStockAlertSkuRepository;
import com.commercefoundry.domainlayer.notification.adapter.out.persistence.repository.LowStockAlertStateRepository;
import com.commercefoundry.domainlayer.notification.adapter.out.persistence.repository.NotificationRepository;
import com.commercefoundry.domainlayer.notification.adapter.out.stream.PaymentAnomalyAlertPushAdapter;
import com.commercefoundry.domainlayer.notification.application.exception.NotificationErrorCode;
import com.commercefoundry.domainlayer.notification.application.exception.NotificationException;
import com.commercefoundry.domainlayer.notification.application.model.NotificationStream;
import com.commercefoundry.domainlayer.notification.application.port.out.LowStockObservationPort;
import com.commercefoundry.domainlayer.notification.application.port.out.NotificationPushPort;
import com.commercefoundry.domainlayer.notification.application.port.out.NotificationRecipientPort;
import com.commercefoundry.domainlayer.notification.application.port.out.PaymentAnomalyAlertPushPort;
import com.commercefoundry.domainlayer.notification.application.service.processor.LowStockAlertStateProcessor;
import com.commercefoundry.domainlayer.notification.application.service.processor.LowStockObservationProcessor;
import com.commercefoundry.domainlayer.notification.application.service.processor.PaymentAnomalyAlertPushProcessor;
import com.commercefoundry.domainlayer.notification.domain.model.LowStockAlertPolicy;
import com.commercefoundry.domainlayer.notification.domain.model.Notification;
import com.commercefoundry.global.config.LowStockAlertConfig;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 품절 임박 정기 점검(#229) 흐름 slice — 실제 스케줄러 빈 · Facade · 트랜잭션 프록시 · 저장 Adapter · SSE 전달 Adapter.
 * 원격 경계(product 관측 · auth 수신자)와 SSE 연결만 시험 대역이다. 락 포트는 각 시험이 고른다(H2 는 통과, PostgreSQL 은 실제 ShedLock).
 *
 * <p>애플리케이션 component scan · Feign · Kafka 가 없는 가벼운 컨텍스트다 — 전체 {@code @SpringBootTest} 컨텍스트를 늘리지 않는다.
 */
@TestConfiguration(proxyBeanMethods = false)
@ImportAutoConfiguration({DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
    JdbcTemplateAutoConfiguration.class, TransactionAutoConfiguration.class})
@EnableJpaAuditing
@EnableTransactionManagement
@EntityScan(basePackageClasses = NotificationEntity.class)
@Import({LowStockAlertConfig.class, LowStockAlertScheduler.class, LowStockAlertSchedulerFacade.class, LowStockObservationProcessor.class,
    LowStockAlertStateProcessor.class, LowStockAlertStateAdapter.class, NotificationBatchInsertAdapter.class, PaymentAnomalyAlertPushProcessor.class,
    PaymentAnomalyAlertPushAdapter.class})
class LowStockAlertFlowConfig {
    @Bean
    LowStockAlertStateRepository lowStockStates(EntityManagerFactory factory) {
        return repositories(factory).getRepository(LowStockAlertStateRepository.class);
    }

    @Bean
    LowStockAlertSkuRepository lowStockSkus(EntityManagerFactory factory) {
        return repositories(factory).getRepository(LowStockAlertSkuRepository.class);
    }

    @Bean
    NotificationRepository lowStockNotifications(EntityManagerFactory factory) {
        return repositories(factory).getRepository(NotificationRepository.class);
    }

    @Bean
    SnowflakeIdGenerator lowStockIds() {
        return new SnowflakeIdGenerator(3, 9);
    }

    @Bean
    SampleClock lowStockClock() {
        return new SampleClock();
    }

    @Bean(name = "lowStockAlertTicker", defaultCandidate = false)
    SampleTicker lowStockAlertTicker() {
        return new SampleTicker();
    }

    @Bean
    ScriptedSource lowStockSource() {
        return new ScriptedSource();
    }

    @Bean
    ScriptedRecipients lowStockRecipients() {
        return new ScriptedRecipients();
    }

    @Bean
    RecordingStreams lowStockStreams() {
        return new RecordingStreams();
    }

    /** 실제 전달 Adapter 앞에서 enqueue 호출만 센다. */
    @Bean
    @Primary
    CountingPush lowStockCountingPush(PaymentAnomalyAlertPushAdapter delegate) {
        return new CountingPush(delegate);
    }

    private static JpaRepositoryFactory repositories(EntityManagerFactory factory) {
        return new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(factory));
    }

    static final class SampleClock extends Clock {
        volatile Instant now = Instant.parse("2026-11-28T01:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    static final class SampleTicker implements LongSupplier {
        volatile long now;

        @Override
        public long getAsLong() {
            return now;
        }
    }

    /** product 품절 임박 커서 조회 대역. 커서별 페이지를 돌려주고, 지정한 커서에서는 원천 장애(NOTIFICATION_053)를 낸다. */
    static final class ScriptedSource implements LowStockObservationPort {
        final List<Long> cursors = new CopyOnWriteArrayList<>();
        final List<Boolean> calledInTransaction = new CopyOnWriteArrayList<>();
        private volatile Map<Long, LowStockAlertPolicy.Page> pages = Map.of();
        private volatile long failAt = -1;

        /** 첫 페이지 관측 시각 {@code observedAt} 으로, 덩어리 하나를 한 페이지로 낸다. 마지막 덩어리만 {@code hasNext=false}. */
        @SafeVarargs
        final void serve(Instant observedAt, List<Long>... chunks) {
            var next = new HashMap<Long, LowStockAlertPolicy.Page>();
            long after = 0;
            for (int index = 0; index < chunks.length; index++) {
                var ids = chunks[index];
                boolean hasNext = index < chunks.length - 1;
                next.put(after, new LowStockAlertPolicy.Page(after, observedAt.plusMillis(index), ids, hasNext, hasNext ? ids.getLast() : null));
                if (hasNext) {
                    after = ids.getLast();
                }
            }
            pages = Map.copyOf(next);
            failAt = -1;
        }

        void failAt(long cursor) {
            failAt = cursor;
        }

        void reset() {
            cursors.clear();
            calledInTransaction.clear();
            pages = Map.of();
            failAt = -1;
        }

        @Override
        public LowStockAlertPolicy.Page observe(long afterSkuId, int size) {
            cursors.add(afterSkuId);
            calledInTransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
            if (afterSkuId == failAt) {
                throw new NotificationException(NotificationErrorCode.LOW_STOCK_OBSERVATION_UNAVAILABLE);
            }
            var page = pages.get(afterSkuId);
            if (page == null) {
                throw new NotificationException(NotificationErrorCode.LOW_STOCK_OBSERVATION_UNAVAILABLE);
            }
            return page;
        }
    }

    /** auth 활성 대표 관리자 조회 대역. */
    static final class ScriptedRecipients implements NotificationRecipientPort {
        final AtomicInteger calls = new AtomicInteger();
        volatile Supplier<List<Long>> ids = () -> List.of(7L, 8L);

        @Override
        public List<Long> findAllActiveAdminIds() {
            calls.incrementAndGet();
            return ids.get();
        }
    }

    /** 실제 전달 Adapter 의 worker 가 부르는 SSE 연결 대역. 받은 알림과 그때 트랜잭션이 열려 있었는지 남긴다. */
    static final class RecordingStreams implements NotificationPushPort {
        final ConcurrentLinkedQueue<Notification> pushed = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<Boolean> pushedInTransaction = new ConcurrentLinkedQueue<>();
        private volatile CountDownLatch delivered = new CountDownLatch(0);

        void expect(int count) {
            delivered = new CountDownLatch(count);
        }

        boolean await() throws InterruptedException {
            return delivered.await(5, TimeUnit.SECONDS);
        }

        void reset() {
            pushed.clear();
            pushedInTransaction.clear();
            delivered = new CountDownLatch(0);
        }

        @Override
        public void register(long recipientMemberId, NotificationStream stream) {
        }

        @Override
        public void unregister(long recipientMemberId, NotificationStream stream) {
        }

        @Override
        public void push(Notification notification) {
            pushedInTransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
            pushed.add(notification);
            delivered.countDown();
        }
    }

    static final class CountingPush implements PaymentAnomalyAlertPushPort {
        final AtomicInteger batches = new AtomicInteger();
        private final PaymentAnomalyAlertPushPort delegate;

        CountingPush(PaymentAnomalyAlertPushPort delegate) {
            this.delegate = delegate;
        }

        @Override
        public void enqueue(List<Notification> notifications) {
            batches.incrementAndGet();
            delegate.enqueue(notifications);
        }
    }
}
