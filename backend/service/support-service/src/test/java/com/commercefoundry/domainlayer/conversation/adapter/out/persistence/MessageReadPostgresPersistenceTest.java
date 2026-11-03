package com.commercefoundry.domainlayer.conversation.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.commercefoundry.domainlayer.conversation.domain.model.ConversationLocale;
import com.commercefoundry.domainlayer.guestsession.application.exception.GuestSessionException;
import com.commercefoundry.domainlayer.guestsession.application.exception.GuestSessionErrorCode;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
@EnabledWithDockerOrOnCi
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
    "spring.datasource.type=com.zaxxer.hikari.HikariDataSource",
    "spring.jpa.properties.hibernate.generate_statistics=true",
    "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.properties.hibernate.default_schema=support",
    "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false"
})
class MessageReadPostgresPersistenceTest extends MessageReadPersistenceContract {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/support-schema.sql");

    @Test
    void guestReadAuthorizationHoldsRevokeUntilReadTransactionCommits() throws Exception {
        var guest = guests.issue();
        var parent = conversations.createForGuest(guest.token(), ConversationLocale.KO);
        var read = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var attempting = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var holder = executor.submit(() -> tx.executeWithoutResult(status -> {
                messages.getForGuest(parent.id(), guest.token(), 0, 1);
                read.countDown();
                await(release);
            }));
            Future<?> revoke;
            try {
                ready(read, holder);
                revoke = executor.submit(() -> {
                    attempting.countDown();
                    guests.revoke(guest.id());
                });
                ready(attempting, revoke);
                assertThatThrownBy(() -> revoke.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
            } finally {
                release.countDown();
            }
            holder.get(10, TimeUnit.SECONDS);
            revoke.get(10, TimeUnit.SECONDS);
        }
        assertThat(messageRepository.count()).isZero();
        assertThat(repository.findById(parent.id()).orElseThrow().getLastSeq()).isZero();
    }

    @Test
    void revokeHoldingGuestRowRejectsCursorThenCommittedRevokeRejectsCursor() throws Exception {
        var guest = guests.issue();
        var parent = conversations.createForGuest(guest.token(), ConversationLocale.KO);
        messages.getForGuest(parent.id(), guest.token(), 0, 1);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var holder = executor.submit(() -> tx.executeWithoutResult(status -> {
                guests.revoke(guest.id());
                locked.countDown();
                await(release);
            }));
            try {
                ready(locked, holder);
                executor.submit(() -> assertThatThrownBy(() -> messages.getForGuest(parent.id(), guest.token(), 0, 1))
                    .isInstanceOfSatisfying(GuestSessionException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(GuestSessionErrorCode.LOCK_UNAVAILABLE))).get(5, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
            holder.get(10, TimeUnit.SECONDS);
        }
        assertThatThrownBy(() -> messages.getForGuest(parent.id(), guest.token(), 0, 1))
            .isInstanceOfSatisfying(GuestSessionException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(GuestSessionErrorCode.UNAVAILABLE));
        assertThat(messageRepository.count()).isZero();
    }

    private static void await(CountDownLatch signal) {
        try {
            if (!signal.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for transaction coordination");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted transaction coordination", failure);
        }
    }

    private static void ready(CountDownLatch signal, Future<?> worker) throws Exception {
        if (!signal.await(10, TimeUnit.SECONDS)) {
            worker.get(1, TimeUnit.SECONDS);
            throw new AssertionError("Worker completed without reaching coordination point");
        }
    }
}
