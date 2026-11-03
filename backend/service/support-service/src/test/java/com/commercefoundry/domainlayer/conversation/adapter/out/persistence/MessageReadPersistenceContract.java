package com.commercefoundry.domainlayer.conversation.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.conversation.adapter.out.persistence.entity.ConversationEntity;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.commercefoundry.domainlayer.conversation.application.info.MessageInfo;
import com.commercefoundry.domainlayer.conversation.application.info.MessagePageInfo;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import org.hibernate.SessionFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({SupportServiceFeaturesConfig.class, ConversationRepositoryAdapter.class, ConversationMapperImpl.class, ConversationProcessor.class,
    ConversationInternalFacade.class, GuestSessionStoreAdapter.class, GuestSessionMapperImpl.class, GuestSessionCryptoAdapter.class,
    GuestSessionProcessor.class, MessageRepositoryAdapter.class, MessageMapperImpl.class, MessageContentPolicy.class,
    ConversationMessageProcessor.class, ConversationMessageInternalFacade.class, MessageReadPersistenceContract.Config.class})
abstract class MessageReadPersistenceContract {
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
    @MockitoSpyBean MessageRepositoryAdapter messageAdapter;
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
        reset(messageAdapter);
    }

    @Test
    void ownerAndGuestAuthenticationPrecedePaginationValidation() {
        var member = conversations.createForMember(11, ConversationLocale.KO);
        var a = guests.issue();
        var b = guests.issue();
        var guest = conversations.createForGuest(a.token(), ConversationLocale.KO);
        messages.appendForMember(member.id(), 11, KEY, "member");
        messages.appendForGuest(guest.id(), a.token(), KEY, "guest");
        assertThat(messages.getForGuest(guest.id(), a.token(), 0, 1).messages()).hasSize(1);
        failure(() -> messages.getForMember(member.id(), 12, -1, 0), ConversationErrorCode.NOT_FOUND);
        failure(() -> messages.getForMember(guest.id(), 11, -1, 0), ConversationErrorCode.NOT_FOUND);
        failure(() -> messages.getForMember(Long.MAX_VALUE, 11, -1, 0), ConversationErrorCode.NOT_FOUND);
        failure(() -> messages.getForMember(member.id(), 0, -1, 0), ConversationErrorCode.INVALID_OWNER);
        failure(() -> messages.getForGuest(member.id(), a.token(), -1, 0), ConversationErrorCode.NOT_FOUND);
        failure(() -> messages.getForGuest(guest.id(), b.token(), -1, 0), ConversationErrorCode.NOT_FOUND);
        failure(() -> messages.getForGuest(Long.MAX_VALUE, a.token(), -1, 0), ConversationErrorCode.NOT_FOUND);
        assertThatThrownBy(() -> messages.getForGuest(guest.id(), "invalid", -1, 0)).isInstanceOf(GuestSessionException.class);
        when(clock.instant()).thenReturn(a.expiresAt());
        assertThatThrownBy(() -> messages.getForGuest(guest.id(), a.token(), 0, 1)).isInstanceOf(GuestSessionException.class);
        when(clock.instant()).thenReturn(NOW);
        guests.revoke(a.id());
        assertThatThrownBy(() -> messages.getForGuest(guest.id(), a.token(), 0, 1)).isInstanceOf(GuestSessionException.class);
    }

    @Test
    void emptyAndBoundaryCursorsAndPageSizesHaveExplicitContracts() {
        var parent = conversations.createForMember(11, ConversationLocale.KO);
        assertThat(messages.getForMember(parent.id(), 11, 0, 100)).isEqualTo(new MessagePageInfo(List.of(), false, 0, 0));
        failure(() -> messages.getForMember(parent.id(), 11, -1, 1), ConversationErrorCode.INVALID_MESSAGE_CURSOR);
        failure(() -> messages.getForMember(parent.id(), 11, 1, 1), ConversationErrorCode.INVALID_MESSAGE_CURSOR);
        failure(() -> messages.getForMember(parent.id(), 11, 0, 0), ConversationErrorCode.INVALID_MESSAGE_PAGE_SIZE);
        failure(() -> messages.getForMember(parent.id(), 11, 0, 101), ConversationErrorCode.INVALID_MESSAGE_PAGE_SIZE);
        messages.appendForMember(parent.id(), 11, KEY, "hello");
        assertThat(messages.getForMember(parent.id(), 11, 0, 1).nextCursor()).isEqualTo(1);
        assertThat(messages.getForMember(parent.id(), 11, 1, 1)).isEqualTo(new MessagePageInfo(List.of(), false, 1, 1));
        failure(() -> messages.getForMember(parent.id(), 11, 2, 1), ConversationErrorCode.INVALID_MESSAGE_CURSOR);
    }

    @Test
    void ascendingPagesAreDatabaseCappedAndHaveNoCountQueryOrGaps() {
        var parent = conversations.createForMember(11, ConversationLocale.KO);
        for (int i = 0; i < 103; i++) {
            messages.appendForMember(parent.id(), 11, UUID.randomUUID().toString(), "message " + i);
        }
        var statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        var first = messages.getForMember(parent.id(), 11, 0, 100);
        assertThat(first.messages()).hasSize(100);
        assertThat(first.hasNext()).isTrue();
        assertThat(first.nextCursor()).isEqualTo(100);
        assertThat(first.lastSeq()).isEqualTo(103);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
        assertThat(statistics.getEntityLoadCount()).isEqualTo(101);
        verify(messageAdapter).findByConversationIdAndSequenceRange(parent.id(), 0, 103, 101);
        List<Long> sequences = new ArrayList<>(first.messages().stream().map(MessageInfo::seq).toList());
        var second = messages.getForMember(parent.id(), 11, first.nextCursor(), 1);
        while (true) {
            sequences.addAll(second.messages().stream().map(MessageInfo::seq).toList());
            if (!second.hasNext()) {
                break;
            }
            second = messages.getForMember(parent.id(), 11, second.nextCursor(), 1);
        }
        assertThat(sequences).containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, 103).boxed().toList());
        assertThat(second.nextCursor()).isEqualTo(103);
    }

    @Test
    void readingAllStatesPreservesStoredBodyAndDoesNotExposeCredentials() {
        for (ConversationStatus state : ConversationStatus.values()) {
            var parent = conversations.createForMember(11, ConversationLocale.KO);
            messages.appendForMember(parent.id(), 11, KEY, "Remaining private name [PHONE]");
            setState(parent.id(), state, 1);
            var page = messages.getForMember(parent.id(), 11, 0, 100);
            assertThat(page.messages().getFirst().content()).isEqualTo("Remaining private name [PHONE]");
            assertThat(page.toString()).doesNotContain("Remaining", "[PHONE]", KEY);
            assertThatThrownBy(() -> page.messages().clear()).isInstanceOf(UnsupportedOperationException.class);
            var credential = guests.issue();
            var guest = conversations.createForGuest(credential.token(), ConversationLocale.KO);
            messages.appendForGuest(guest.id(), credential.token(), KEY, "guest body");
            setState(guest.id(), state, 1);
            assertThat(messages.getForGuest(guest.id(), credential.token(), 0, 100).messages()).hasSize(1);
        }
        assertThat(java.util.Arrays.stream(MessageInfo.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName))
            .containsExactly("id", "conversationId", "seq", "authorType", "status", "content");
        var source = new ArrayList<MessageInfo>();
        var safe = new MessagePageInfo(source, false, 0, 0);
        source.add(new MessageInfo(1, 1, 1, MessageAuthorType.CUSTOMER, MessageStatus.COMPLETED, "secret"));
        assertThat(safe.messages()).isEmpty();
    }

    @Test
    void appendCommittedBetweenSelectsStaysAboveThisPagesUpperBound() {
        var parent = conversations.createForMember(11, ConversationLocale.KO);
        messages.appendForMember(parent.id(), 11, KEY, "first");
        var independent = independent();
        doAnswer(invocation -> {
            independent.executeWithoutResult(status -> messages.appendForMember(parent.id(), 11, NEXT_KEY, "second"));
            return invocation.callRealMethod();
        }).when(messageAdapter).findByConversationIdAndSequenceRange(parent.id(), 0, 1, 101);
        var first = messages.getForMember(parent.id(), 11, 0, 100);
        assertThat(first.lastSeq()).isEqualTo(1);
        assertThat(first.messages()).extracting(MessageInfo::seq).containsExactly(1L);
        assertThat(first.hasNext()).isFalse();
        var next = messages.getForMember(parent.id(), 11, first.nextCursor(), 100);
        assertThat(next.lastSeq()).isEqualTo(2);
        assertThat(next.messages()).extracting(MessageInfo::seq).containsExactly(2L);
    }

    @Test
    void scalarUpperBoundIgnoresStaleManagedParentAndCallerReadsItsOwnWrites() {
        var parent = conversations.createForMember(11, ConversationLocale.KO);
        tx.executeWithoutResult(status -> {
            var stale = repository.findById(parent.id()).orElseThrow();
            independent().executeWithoutResult(inner -> messages.appendForMember(parent.id(), 11, KEY, "committed"));
            assertThat(stale.getLastSeq()).isZero();
            assertThat(messages.getForMember(parent.id(), 11, 0, 100).lastSeq()).isEqualTo(1);
        });
        var credential = guests.issue();
        var guest = conversations.createForGuest(credential.token(), ConversationLocale.KO);
        tx.executeWithoutResult(status -> {
            messages.appendForMember(parent.id(), 11, NEXT_KEY, "own write");
            messages.appendForGuest(guest.id(), credential.token(), KEY, "guest own write");
            assertThat(messages.getForMember(parent.id(), 11, 1, 100).messages()).extracting(MessageInfo::seq).containsExactly(2L);
            assertThat(messages.getForGuest(guest.id(), credential.token(), 0, 100).messages()).extracting(MessageInfo::seq).containsExactly(1L);
            status.setRollbackOnly();
        });
        assertThat(messageRepository.count()).isEqualTo(1);
        assertThat(repository.findById(parent.id()).orElseThrow().getLastSeq()).isEqualTo(1);
        assertThat(repository.findById(guest.id()).orElseThrow().getLastSeq()).isZero();
    }

    protected TransactionTemplate independent() {
        var independent = new TransactionTemplate(manager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return independent;
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
}
