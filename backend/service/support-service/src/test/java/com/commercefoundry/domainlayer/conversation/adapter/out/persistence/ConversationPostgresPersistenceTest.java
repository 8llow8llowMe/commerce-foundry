package com.commercefoundry.domainlayer.conversation.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import com.commercefoundry.domainlayer.guestsession.adapter.out.persistence.GuestSessionStoreAdapter;
import com.commercefoundry.domainlayer.guestsession.application.exception.GuestSessionException;
import com.commercefoundry.domainlayer.guestsession.application.exception.GuestSessionErrorCode;
import com.commercefoundry.domainlayer.conversation.domain.model.ConversationLocale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DataJpaTest
@Testcontainers
@EnabledWithDockerOrOnCi
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
    "spring.datasource.type=com.zaxxer.hikari.HikariDataSource",
    "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.properties.hibernate.default_schema=support",
    "spring.cloud.discovery.enabled=false", "eureka.client.enabled=false"
})
class ConversationPostgresPersistenceTest extends ConversationPersistenceContract {
    @MockitoSpyBean GuestSessionStoreAdapter adapter;

    @Test
    void heldGuestLockRejectsConcurrentCreateAndReadWithoutWritingThenAllowsRetry() throws Exception {
        var guest = guests.issue();
        var conversation = conversations.createForGuest(guest.token(), ConversationLocale.KO);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var holder = executor.submit(() -> tx.executeWithoutResult(status -> {
                guests.resolveForUpdate(guest.token());
                locked.countDown();
                await(release);
            }));
            try {
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
                var contender = executor.submit(() -> {
                    busy(() -> conversations.createForGuest(guest.token(), ConversationLocale.KO));
                    busy(() -> conversations.getForGuest(conversation.id(), guest.token()));
                });
                contender.get(5, TimeUnit.SECONDS);
                assertThat(repository.count()).isEqualTo(1);
            } finally {
                release.countDown();
            }
            holder.get(10, TimeUnit.SECONDS);
        }
        assertThat(conversations.createForGuest(guest.token(), ConversationLocale.EN).id()).isPositive();
        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    void revokeWaitsWhenGuestAuthorizationHasAlreadyLockedTheRow() throws Exception {
        var guest = guests.issue();
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var revokeRead = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object value = invocation.callRealMethod();
            revokeRead.countDown();
            return value;
        }).when(adapter).findById(anyLong());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var holder = executor.submit(() -> tx.executeWithoutResult(status -> {
                guests.resolveForUpdate(guest.token());
                conversations.createForGuest(guest.token(), ConversationLocale.KO);
                locked.countDown();
                await(release);
            }));
            try {
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
                var revoke = executor.submit(() -> guests.revoke(guest.id()));
                assertThat(revokeRead.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(revoke.isDone()).isFalse();
                release.countDown();
                holder.get(10, TimeUnit.SECONDS);
                revoke.get(10, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
        } finally {
            reset(adapter);
        }
        assertThat(repository.count()).isEqualTo(1);
        unavailable(() -> conversations.createForGuest(guest.token(), ConversationLocale.KO));
    }

    @Test
    void revokeWriteFirstCausesNowaitConflictThenCommittedRevokeCausesUnauthorized() throws Exception {
        var guest = guests.issue();
        var written = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var revoke = executor.submit(() -> tx.executeWithoutResult(status -> {
                guests.revoke(guest.id());
                written.countDown();
                await(release);
            }));
            try {
                assertThat(written.await(10, TimeUnit.SECONDS)).isTrue();
                executor.submit(() -> busy(() -> conversations.createForGuest(guest.token(), ConversationLocale.KO))).get(5, TimeUnit.SECONDS);
                assertThat(repository.count()).isZero();
            } finally {
                release.countDown();
            }
            revoke.get(10, TimeUnit.SECONDS);
        }
        unavailable(() -> conversations.createForGuest(guest.token(), ConversationLocale.KO));
    }

    private void busy(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(GuestSessionException.class, failure -> {
            assertThat(failure.getErrorCode()).isEqualTo(GuestSessionErrorCode.LOCK_UNAVAILABLE);
            assertThat(failure.getMessage()).isEqualTo("Guest session is busy.");
            assertThat(failure.getCause()).isNull();
        });
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for transaction coordination");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted transaction coordination", failure);
        }
    }

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine").withInitScript("ddl/support-schema.sql");
}
