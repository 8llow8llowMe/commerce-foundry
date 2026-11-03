package com.commercefoundry.domainlayer.conversation.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.conversation.adapter.out.persistence.entity.ConversationEntity;
import com.commercefoundry.domainlayer.conversation.adapter.out.persistence.repository.ConversationRepository;
import com.commercefoundry.domainlayer.conversation.application.exception.ConversationErrorCode;
import com.commercefoundry.domainlayer.conversation.application.exception.ConversationException;
import com.commercefoundry.domainlayer.conversation.application.mapper.ConversationMapperImpl;
import com.commercefoundry.domainlayer.conversation.application.port.in.ConversationInternalUseCase;
import com.commercefoundry.domainlayer.conversation.application.service.ConversationInternalFacade;
import com.commercefoundry.domainlayer.conversation.application.service.processor.ConversationProcessor;
import com.commercefoundry.domainlayer.conversation.domain.model.ConversationChannel;
import com.commercefoundry.domainlayer.conversation.domain.model.ConversationLocale;
import com.commercefoundry.domainlayer.conversation.domain.model.ConversationStatus;
import com.commercefoundry.domainlayer.guestsession.adapter.out.crypto.GuestSessionCryptoAdapter;
import com.commercefoundry.domainlayer.guestsession.adapter.out.persistence.GuestSessionStoreAdapter;
import com.commercefoundry.domainlayer.guestsession.adapter.out.persistence.repository.GuestSessionRepository;
import com.commercefoundry.domainlayer.guestsession.application.exception.GuestSessionErrorCode;
import com.commercefoundry.domainlayer.guestsession.application.exception.GuestSessionException;
import com.commercefoundry.domainlayer.guestsession.application.mapper.GuestSessionMapperImpl;
import com.commercefoundry.domainlayer.guestsession.application.service.processor.GuestSessionProcessor;
import com.commercefoundry.global.config.SupportServiceFeaturesConfig;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({SupportServiceFeaturesConfig.class, ConversationRepositoryAdapter.class, ConversationMapperImpl.class, ConversationProcessor.class,
    ConversationInternalFacade.class, GuestSessionStoreAdapter.class, GuestSessionMapperImpl.class, GuestSessionCryptoAdapter.class,
    GuestSessionProcessor.class, ConversationPersistenceContract.Config.class})
abstract class ConversationPersistenceContract {
    protected static final Instant NOW = Instant.parse("2026-11-03T00:00:00.123456Z");
    @Autowired ConversationInternalUseCase conversations;
    @Autowired ConversationRepository repository;
    @Autowired GuestSessionRepository guestRepository;
    @Autowired GuestSessionProcessor guests;
    @Autowired GuestSessionCryptoAdapter crypto;
    @Autowired PlatformTransactionManager manager;
    @Autowired Clock clock;
    protected TransactionTemplate tx;

    @TestConfiguration
    static class Config {
        @Bean SnowflakeIdGenerator conversationIds() { return new SnowflakeIdGenerator(1, 1); }
        @Bean Clock conversationClock() { return mock(Clock.class); }
    }

    @BeforeEach
    void clean() {
        tx = new TransactionTemplate(manager);
        tx.executeWithoutResult(status -> {
            repository.deleteAllInBatch();
            guestRepository.deleteAllInBatch();
        });
        when(clock.instant()).thenReturn(NOW);
    }

    @Test
    void ownersAreIsolatedAndCreationIsDeliberatelyNonIdempotent() {
        var firstGuest = guests.issue();
        var secondGuest = guests.issue();
        var member = conversations.createForMember(11, ConversationLocale.KO);
        var duplicate = conversations.createForMember(11, ConversationLocale.KO);
        var guest = conversations.createForGuest(firstGuest.token(), ConversationLocale.EN);
        assertThat(duplicate.id()).isNotEqualTo(member.id());
        assertThat(repository.count()).isEqualTo(3);
        assertThat(conversations.getForMember(member.id(), 11)).isEqualTo(member);
        assertThat(conversations.getForGuest(guest.id(), firstGuest.token())).isEqualTo(guest);
        assertThat(member.status()).isEqualTo(ConversationStatus.AI_ACTIVE);
        assertThat(guest.channel()).isEqualTo(ConversationChannel.WEB);
        assertThat(guest.lastSeq()).isZero();
        missing(() -> conversations.getForMember(member.id(), 12));
        missing(() -> conversations.getForMember(guest.id(), 11));
        missing(() -> conversations.getForGuest(member.id(), firstGuest.token()));
        missing(() -> conversations.getForGuest(guest.id(), secondGuest.token()));
        missing(() -> conversations.getForMember(Long.MAX_VALUE, 11));
        missing(() -> conversations.getForGuest(Long.MAX_VALUE, firstGuest.token()));
        assertThat(repository.findById(guest.id()).orElseThrow().getGuestSessionId()).isEqualTo(firstGuest.id());
        assertThat(repository.findById(member.id()).orElseThrow().getMemberId()).isEqualTo(11);
    }

    @Test
    void invalidExpiredAndRevokedCredentialsCannotCreateOrRead() {
        var issued = guests.issue();
        var conversation = conversations.createForGuest(issued.token(), ConversationLocale.KO);
        for (String token : new String[] {null, "malformed", crypto.generate()}) {
            unavailable(() -> conversations.createForGuest(token, ConversationLocale.KO));
            unavailable(() -> conversations.getForGuest(conversation.id(), token));
        }
        when(clock.instant()).thenReturn(issued.expiresAt());
        unavailable(() -> conversations.createForGuest(issued.token(), ConversationLocale.KO));
        unavailable(() -> conversations.getForGuest(conversation.id(), issued.token()));
        when(clock.instant()).thenReturn(NOW);
        guests.revoke(issued.id());
        unavailable(() -> conversations.createForGuest(issued.token(), ConversationLocale.KO));
        unavailable(() -> conversations.getForGuest(conversation.id(), issued.token()));
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void refreshRejectsRevokedCredentialAlreadyLoadedInPersistenceContext() {
        var guest = guests.issue();
        var independent = new TransactionTemplate(manager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            assertThat(guests.resolve(guest.token()).id()).isEqualTo(guest.id());
            independent.executeWithoutResult(inner -> guests.revoke(guest.id()));
            unavailable(() -> conversations.createForGuest(guest.token(), ConversationLocale.KO));
            status.setRollbackOnly();
        });
        assertThat(repository.count()).isZero();
    }

    @Test
    void creationParticipatesInCallerRollbackIncludingFlushedGuestCreation() {
        var guest = guests.issue();
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            conversations.createForMember(11, ConversationLocale.KO);
            conversations.createForGuest(guest.token(), ConversationLocale.EN);
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(repository.count()).isZero();
        assertThat(guests.resolve(guest.token()).id()).isEqualTo(guest.id());
    }

    @Test
    void databaseRejectsInvalidOwnersAndNegativeSequenceIndependentlyOfDomain() {
        for (Long[] owners : new Long[][] {{null, null}, {1L, 2L}, {0L, null}, {-1L, null}, {null, 0L}, {null, -1L}}) {
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> repository.saveAndFlush(entity(owners[0], owners[1], 0))))
                .isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> repository.saveAndFlush(entity(1L, null, -1))))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(repository.count()).isZero();
    }

    @Test
    void invalidTrustedMemberAndLocaleAreRejectedBeforePersistence() {
        assertThatThrownBy(() -> conversations.createForMember(0, ConversationLocale.KO)).isInstanceOfSatisfying(ConversationException.class,
            failure -> assertThat(failure.getErrorCode()).isEqualTo(ConversationErrorCode.INVALID_OWNER));
        assertThatThrownBy(() -> conversations.createForMember(1, null)).isInstanceOfSatisfying(ConversationException.class,
            failure -> assertThat(failure.getErrorCode()).isEqualTo(ConversationErrorCode.INVALID_LOCALE));
        assertThat(repository.count()).isZero();
    }

    private ConversationEntity entity(Long memberId, Long guestSessionId, long sequence) {
        return ConversationEntity.builder().id(1L).memberId(memberId).guestSessionId(guestSessionId).locale(ConversationLocale.KO)
            .channel(ConversationChannel.WEB).status(ConversationStatus.AI_ACTIVE).lastSeq(sequence).build();
    }

    protected void unavailable(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(GuestSessionException.class,
            failure -> assertThat(failure.getErrorCode()).isEqualTo(GuestSessionErrorCode.UNAVAILABLE));
    }

    private void missing(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ConversationException.class, failure -> {
            assertThat(failure.getErrorCode()).isEqualTo(ConversationErrorCode.NOT_FOUND);
            assertThat(failure.getMessage()).isEqualTo("Conversation is unavailable.");
        });
    }
}
