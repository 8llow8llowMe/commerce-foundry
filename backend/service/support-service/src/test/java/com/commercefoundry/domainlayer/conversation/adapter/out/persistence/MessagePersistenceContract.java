package com.commercefoundry.domainlayer.conversation.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.conversation.adapter.out.persistence.entity.ConversationEntity;
import com.commercefoundry.domainlayer.conversation.adapter.out.persistence.entity.MessageEntity;
import com.commercefoundry.domainlayer.conversation.adapter.out.persistence.repository.ConversationRepository;
import com.commercefoundry.domainlayer.conversation.adapter.out.persistence.repository.MessageRepository;
import com.commercefoundry.domainlayer.conversation.application.exception.ConversationErrorCode;
import com.commercefoundry.domainlayer.conversation.application.exception.ConversationException;
import com.commercefoundry.domainlayer.conversation.application.mapper.ConversationMapperImpl;
import com.commercefoundry.domainlayer.conversation.application.mapper.MessageMapperImpl;
import com.commercefoundry.domainlayer.conversation.application.port.in.ConversationInternalUseCase;
import com.commercefoundry.domainlayer.conversation.application.port.in.ConversationMessageInternalUseCase;
import com.commercefoundry.domainlayer.conversation.application.port.out.ConversationRepositoryPort;
import com.commercefoundry.domainlayer.conversation.application.service.ConversationInternalFacade;
import com.commercefoundry.domainlayer.conversation.application.service.ConversationMessageInternalFacade;
import com.commercefoundry.domainlayer.conversation.application.service.processor.ConversationProcessor;
import com.commercefoundry.domainlayer.conversation.application.service.processor.ConversationMessageProcessor;
import com.commercefoundry.domainlayer.conversation.application.service.processor.MessageContentPolicy;
import com.commercefoundry.domainlayer.conversation.domain.model.ConversationChannel;
import com.commercefoundry.domainlayer.conversation.domain.model.ConversationLocale;
import com.commercefoundry.domainlayer.conversation.domain.model.ConversationStatus;
import com.commercefoundry.domainlayer.conversation.domain.model.MessageAuthorType;
import com.commercefoundry.domainlayer.conversation.domain.model.MessageStatus;
import com.commercefoundry.domainlayer.guestsession.adapter.out.crypto.GuestSessionCryptoAdapter;
import com.commercefoundry.domainlayer.guestsession.adapter.out.persistence.GuestSessionStoreAdapter;
import com.commercefoundry.domainlayer.guestsession.adapter.out.persistence.repository.GuestSessionRepository;
import com.commercefoundry.domainlayer.guestsession.application.exception.GuestSessionException;
import com.commercefoundry.domainlayer.guestsession.application.mapper.GuestSessionMapperImpl;
import com.commercefoundry.domainlayer.guestsession.application.service.processor.GuestSessionProcessor;
import com.commercefoundry.global.config.SupportServiceFeaturesConfig;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import jakarta.persistence.EntityManager;
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
    GuestSessionProcessor.class, MessageRepositoryAdapter.class, MessageMapperImpl.class, MessageContentPolicy.class,
    ConversationMessageProcessor.class, ConversationMessageInternalFacade.class, MessagePersistenceContract.Config.class})
abstract class MessagePersistenceContract {
    protected static final String KEY = "12345678-1234-1234-1234-123456789abc";
    protected static final String NEXT_KEY = "12345678-1234-1234-1234-123456789abd";
    protected static final Instant NOW = Instant.parse("2026-11-03T00:00:00Z");
    @Autowired ConversationInternalUseCase conversations;
    @Autowired ConversationMessageInternalUseCase messages;
    @Autowired ConversationRepository repository;
    @Autowired MessageRepository messageRepository;
    @Autowired ConversationRepositoryPort conversationPort;
    @Autowired GuestSessionRepository guestRepository;
    @Autowired GuestSessionProcessor guests;
    @Autowired PlatformTransactionManager manager;
    @Autowired EntityManager entityManager;
    @Autowired Clock clock;
    protected TransactionTemplate tx;

    @TestConfiguration
    static class Config {
        @Bean SnowflakeIdGenerator messageIds() { return new SnowflakeIdGenerator(1, 1); }
        @Bean Clock messageClock() { return mock(Clock.class); }
    }

    @BeforeEach
    void clean() {
        tx = new TransactionTemplate(manager);
        tx.executeWithoutResult(status -> {
            messageRepository.deleteAllInBatch();
            repository.deleteAllInBatch();
            guestRepository.deleteAllInBatch();
        });
        when(clock.instant()).thenReturn(NOW);
    }

    @Test
    void replayIsOneRowAndDoesNotAdvanceParentVersionOrSequence() {
        var parent = conversations.createForMember(11, ConversationLocale.KO);
        var first = messages.appendForMember(parent.id(), 11, KEY, "  ０１０－１１１１－２２２２ ");
        var before = repository.findById(parent.id()).orElseThrow();
        assertThat(messages.appendForMember(parent.id(), 11, KEY, "010-3333-4444")).isEqualTo(first);
        assertThat(first.content()).isEqualTo("[PHONE]");
        assertThat(first.toString()).doesNotContain("[PHONE]", KEY);
        assertThat(messageRepository.count()).isEqualTo(1);
        var after = repository.findById(parent.id()).orElseThrow();
        assertThat(after.getVersion()).isEqualTo(before.getVersion());
        assertThat(after.getLastSeq()).isEqualTo(1);
        failure(() -> messages.appendForMember(parent.id(), 11, KEY, "changed"), ConversationErrorCode.MESSAGE_CONFLICT);
        assertThat(messages.appendForMember(parent.id(), 11, NEXT_KEY, "next").seq()).isEqualTo(2);
        var other = conversations.createForMember(11, ConversationLocale.KO);
        assertThat(messages.appendForMember(other.id(), 11, KEY, "different conversation").seq()).isEqualTo(1);
        var stored = messageRepository.findByConversationIdAndClientMessageId(parent.id(), KEY).orElseThrow();
        assertThat(stored.getAuthorMemberId()).isEqualTo(11);
        assertThat(stored.getAuthorGuestSessionId()).isNull();
    }

    @Test
    void authorizationAlwaysPrecedesReplayAndInputValidation() {
        var member = conversations.createForMember(11, ConversationLocale.KO);
        var a = guests.issue();
        var b = guests.issue();
        var guest = conversations.createForGuest(a.token(), ConversationLocale.KO);
        messages.appendForMember(member.id(), 11, KEY, "hello");
        messages.appendForGuest(guest.id(), a.token(), KEY, "hello");
        failure(() -> messages.appendForMember(member.id(), 12, null, null), ConversationErrorCode.NOT_FOUND);
        failure(() -> messages.appendForMember(guest.id(), 11, KEY, "hello"), ConversationErrorCode.NOT_FOUND);
        failure(() -> messages.appendForGuest(member.id(), a.token(), KEY, "hello"), ConversationErrorCode.NOT_FOUND);
        failure(() -> messages.appendForGuest(guest.id(), b.token(), KEY, "hello"), ConversationErrorCode.NOT_FOUND);
        failure(() -> messages.appendForMember(member.id(), 0, KEY, "hello"), ConversationErrorCode.INVALID_OWNER);
        when(clock.instant()).thenReturn(a.expiresAt());
        assertThatThrownBy(() -> messages.appendForGuest(guest.id(), a.token(), KEY, "hello")).isInstanceOf(GuestSessionException.class);
        when(clock.instant()).thenReturn(NOW);
        guests.revoke(a.id());
        assertThatThrownBy(() -> messages.appendForGuest(guest.id(), a.token(), KEY, "hello")).isInstanceOf(GuestSessionException.class);
        assertThat(messageRepository.count()).isEqualTo(2);
        assertThat(messageRepository.findByConversationIdAndClientMessageId(guest.id(), KEY).orElseThrow().getAuthorGuestSessionId()).isEqualTo(a.id());
    }

    @Test
    void stateAppliesOnlyToNewKeysAndGuestsRemainAiOnly() {
        for (ConversationStatus state : ConversationStatus.values()) {
            var member = conversations.createForMember(11, ConversationLocale.KO);
            var original = messages.appendForMember(member.id(), 11, KEY, "hello");
            setState(member.id(), state, 1);
            assertThat(messages.appendForMember(member.id(), 11, KEY, "hello")).isEqualTo(original);
            if (state == ConversationStatus.CLOSED || state == ConversationStatus.RESOLVED) {
                failure(() -> messages.appendForMember(member.id(), 11, NEXT_KEY, "next"), ConversationErrorCode.MESSAGE_STATE_UNAVAILABLE);
            } else {
                assertThat(messages.appendForMember(member.id(), 11, NEXT_KEY, "next").seq()).isEqualTo(2);
            }
            var credential = guests.issue();
            var guest = conversations.createForGuest(credential.token(), ConversationLocale.KO);
            messages.appendForGuest(guest.id(), credential.token(), KEY, "hello");
            setState(guest.id(), state, 1);
            assertThat(messages.appendForGuest(guest.id(), credential.token(), KEY, "hello").seq()).isEqualTo(1);
            if (state != ConversationStatus.AI_ACTIVE) {
                failure(() -> messages.appendForGuest(guest.id(), credential.token(), NEXT_KEY, "next"), ConversationErrorCode.MESSAGE_STATE_UNAVAILABLE);
            }
        }
    }

    @Test
    void callerRollbackRestoresBothRowsAndSequenceAndOverflowCannotWrite() {
        var member = conversations.createForMember(11, ConversationLocale.KO);
        var guest = guests.issue();
        var guestConversation = conversations.createForGuest(guest.token(), ConversationLocale.KO);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            messages.appendForMember(member.id(), 11, KEY, "hello");
            messages.appendForGuest(guestConversation.id(), guest.token(), KEY, "hello");
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(messageRepository.count()).isZero();
        assertThat(repository.findById(member.id()).orElseThrow().getLastSeq()).isZero();
        assertThat(repository.findById(guestConversation.id()).orElseThrow().getLastSeq()).isZero();
        setState(member.id(), ConversationStatus.AI_ACTIVE, Long.MAX_VALUE);
        failure(() -> messages.appendForMember(member.id(), 11, KEY, "hello"), ConversationErrorCode.SEQUENCE_EXHAUSTED);
        assertThat(messageRepository.count()).isZero();
    }

    @Test
    void messageInsertFailureAfterParentFlushRollsBackSequenceAndVersion() {
        var parent = conversations.createForMember(11, ConversationLocale.KO);
        // An intentionally inconsistent preexisting row forces the real insert's seq unique constraint to fail.
        tx.executeWithoutResult(status -> messageRepository.saveAndFlush(entity(9001, parent.id(), 1, 11L, null, NEXT_KEY, "existing")));
        var before = repository.findById(parent.id()).orElseThrow();
        assertThatThrownBy(() -> messages.appendForMember(parent.id(), 11, KEY, "new"))
            .isInstanceOf(sequenceConflictType()).satisfies(failure -> {
                if (failure instanceof ConversationException exception) {
                    assertThat(exception.getErrorCode()).isEqualTo(ConversationErrorCode.MESSAGE_CONFLICT);
                    assertThat(exception.getCause()).isNull();
                }
            });
        var after = repository.findById(parent.id()).orElseThrow();
        assertThat(after.getLastSeq()).isZero();
        assertThat(after.getVersion()).isEqualTo(before.getVersion());
        assertThat(messageRepository.count()).isEqualTo(1);
    }

    // H2 reports a suffixed index name; only PostgreSQL's exact constraint name is classified.
    Class<? extends RuntimeException> sequenceConflictType() {
        return org.springframework.dao.DataIntegrityViolationException.class;
    }

    @Test
    void databaseChecksAndBothUniqueConstraintsRejectDirectInvalidWrites() {
        for (Long[] actor : new Long[][] {{null, null}, {1L, 2L}, {0L, null}, {null, -1L}}) {
            invalid(entity(100, 1, 1, actor[0], actor[1], KEY, "hello"));
        }
        invalid(entity(0, 1, 1, 1L, null, KEY, "hello"));
        invalid(entity(100, 0, 1, 1L, null, KEY, "hello"));
        invalid(entity(100, 1, 0, 1L, null, KEY, "hello"));
        invalid(entity(100, 1, 1, 1L, null, KEY, " "));
        tx.executeWithoutResult(status -> messageRepository.saveAndFlush(entity(100, 1, 1, 1L, null, KEY, "hello")));
        invalid(entity(101, 1, 1, 1L, null, NEXT_KEY, "hello"));
        invalid(entity(102, 1, 2, 1L, null, KEY, "hello"));
        assertThat(messageRepository.count()).isEqualTo(1);
    }

    @Test
    void staleManagedParentRefreshesLatestSequenceAndVersionBeforeAppending() {
        var parent = conversations.createForMember(11, ConversationLocale.KO);
        var independent = new TransactionTemplate(manager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            var stale = repository.findById(parent.id()).orElseThrow();
            assertThat(stale.getLastSeq()).isZero();
            independent.executeWithoutResult(inner -> messages.appendForMember(parent.id(), 11, KEY, "first"));
            assertThat(stale.getLastSeq()).isZero();
            assertThat(messages.appendForMember(parent.id(), 11, NEXT_KEY, "second").seq()).isEqualTo(2);
        });
        assertThat(repository.findById(parent.id()).orElseThrow().getLastSeq()).isEqualTo(2);
    }

    protected void setState(long id, ConversationStatus state, long seq) {
        tx.executeWithoutResult(status -> {
            var current = repository.findById(id).orElseThrow();
            repository.saveAndFlush(ConversationEntity.builder().id(id).memberId(current.getMemberId()).guestSessionId(current.getGuestSessionId())
                .locale(current.getLocale()).channel(current.getChannel()).status(state).lastSeq(seq).version(current.getVersion()).build());
        });
    }

    protected void failure(Runnable operation, ConversationErrorCode code) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(ConversationException.class, error -> {
            assertThat(error.getErrorCode()).isEqualTo(code);
            assertThat(error.getCause()).isNull();
            assertThat(error.getMessage()).isEqualTo(code.getMessage());
        });
    }

    private void invalid(MessageEntity entity) {
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> messageRepository.saveAndFlush(entity))).isInstanceOf(DataIntegrityViolationException.class);
    }

    private MessageEntity entity(long id, long conversationId, long seq, Long member, Long guest, String key, String content) {
        return MessageEntity.builder().id(id).conversationId(conversationId).seq(seq).authorType(MessageAuthorType.CUSTOMER)
            .authorMemberId(member).authorGuestSessionId(guest).status(MessageStatus.COMPLETED).clientMessageId(key).content(content).build();
    }
}
