package com.commercefoundry.domainlayer.conversation.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.conversation.adapter.out.persistence.entity.ConversationEntity;
import com.commercefoundry.domainlayer.conversation.adapter.out.persistence.repository.ConversationRepository;
import com.commercefoundry.domainlayer.conversation.application.exception.ConversationErrorCode;
import com.commercefoundry.domainlayer.conversation.application.exception.ConversationException;
import com.commercefoundry.domainlayer.conversation.application.mapper.ConversationMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PessimisticLockException;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ConversationMessageAdapterTest {
    private final ConversationRepository repository = mock(ConversationRepository.class);
    private final ConversationMapper mapper = mock(ConversationMapper.class);
    private final EntityManager manager = mock(EntityManager.class);
    private final ConversationRepositoryAdapter adapter = new ConversationRepositoryAdapter(repository, mapper, manager);

    @Test
    void ownerIsRecheckedAfterRefreshWithoutConsultingStaleVersion() {
        var entity = mock(ConversationEntity.class);
        when(repository.findIdByIdAndMemberId(1, 11)).thenReturn(Optional.of(1L));
        when(manager.getReference(ConversationEntity.class, 1L)).thenReturn(entity);
        doAnswer(invocation -> {
            when(entity.getMemberId()).thenReturn(12L);
            return null;
        }).when(manager).refresh(entity, LockModeType.PESSIMISTIC_WRITE, Map.of("jakarta.persistence.lock.timeout", 0));
        assertThat(adapter.findByIdAndMemberIdForUpdate(1, 11)).isEmpty();
        verify(entity, never()).getVersion();
        verifyNoInteractions(mapper);
    }

    @Test
    void expectedLockAndOptimisticFailuresDiscardSqlCauses() {
        when(repository.findIdByIdAndMemberId(1, 11)).thenThrow(new PessimisticLockException("sensitive SQL"));
        assertThatThrownBy(() -> adapter.findByIdAndMemberIdForUpdate(1, 11)).isInstanceOfSatisfying(ConversationException.class, error -> {
            assertThat(error.getErrorCode()).isEqualTo(ConversationErrorCode.LOCK_UNAVAILABLE);
            assertThat(error.getCause()).isNull();
            assertThat(error.getMessage()).doesNotContain("SQL");
        });
        reset(repository);
        when(repository.findIdByIdAndMemberId(1, 11)).thenThrow(new jakarta.persistence.OptimisticLockException("sensitive SQL"));
        assertThatThrownBy(() -> adapter.findByIdAndMemberIdForUpdate(1, 11)).isInstanceOfSatisfying(ConversationException.class, error -> {
            assertThat(error.getErrorCode()).isEqualTo(ConversationErrorCode.CONCURRENTLY_CHANGED);
            assertThat(error.getCause()).isNull();
        });
    }
}
