package com.commercefoundry.domainlayer.order.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.commercefoundry.domainlayer.order.application.port.out.OrderStockRestorationRepositoryPort;
import com.commercefoundry.domainlayer.order.domain.model.OrderStockRestoration.Status;
import com.commercefoundry.global.properties.OrderPaymentRestorationProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class PaymentRestorationBacklogProcessorTest {

    private static final Instant NOW = Instant.parse("2026-11-29T03:00:00Z");
    private final OrderStockRestorationRepositoryPort work = mock(OrderStockRestorationRepositoryPort.class);
    private final MutableClock clock = new MutableClock(NOW);
    private final PaymentRestorationBacklogProcessor processor = new PaymentRestorationBacklogProcessor(work,
        new OrderPaymentRestorationProperties(Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofHours(2),
            new OrderPaymentRestorationProperties.Sweep(true)), clock);
    private final Logger logger = (Logger) LoggerFactory.getLogger(PaymentRestorationBacklogProcessor.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void attach() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(logs);
    }

    @Test
    void quietWhenBacklogIsYoungAndNothingHeld() {
        when(work.findOldestCreatedAt(Status.RESTORE_PENDING)).thenReturn(Optional.of(NOW.minus(Duration.ofMinutes(29))));
        processor.warnBacklog();
        assertThat(logs.list).isEmpty();
    }

    @Test
    void quietWhenNothingPending() {
        when(work.findOldestCreatedAt(Status.RESTORE_PENDING)).thenReturn(Optional.empty());
        processor.warnBacklog();
        assertThat(logs.list).isEmpty();
    }

    @Test
    void warnsThenErrorsByOldestAgeWithCountsOnly() {
        when(work.countByStatus(Status.RESTORE_PENDING)).thenReturn(3L);
        when(work.findOldestCreatedAt(Status.RESTORE_PENDING)).thenReturn(Optional.of(NOW.minus(Duration.ofMinutes(30))));
        processor.warnBacklog();
        clock.advance(Duration.ofMinutes(1));
        when(work.findOldestCreatedAt(Status.RESTORE_PENDING)).thenReturn(Optional.of(NOW.minus(Duration.ofHours(2))));
        processor.warnBacklog();

        assertThat(logs.list).extracting(ILoggingEvent::getLevel).containsExactly(Level.WARN, Level.ERROR);
        assertThat(logs.list.get(0).getFormattedMessage()).isEqualTo("payment restoration backlog aging. pending=3, oldestAgeSeconds=1800");
        assertThat(logs.list.get(1).getFormattedMessage()).startsWith("payment restoration backlog too old. pending=3");
    }

    @Test
    void heldWorkWarnsAtMostOncePerMinute() {
        when(work.findOldestCreatedAt(Status.RESTORE_PENDING)).thenReturn(Optional.empty());
        when(work.countByStatus(Status.HELD)).thenReturn(2L);
        processor.warnBacklog();
        clock.advance(Duration.ofSeconds(59));
        processor.warnBacklog();
        clock.advance(Duration.ofSeconds(1));
        processor.warnBacklog();

        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
            .isEqualTo(List.of("payment restoration held for manual review. held=2", "payment restoration held for manual review. held=2"));
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
