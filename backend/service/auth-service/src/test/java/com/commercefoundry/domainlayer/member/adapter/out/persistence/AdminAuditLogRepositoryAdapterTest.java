package com.commercefoundry.domainlayer.member.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.member.adapter.out.log.AdminAuditLogAdapter;
import com.commercefoundry.domainlayer.member.adapter.out.persistence.entity.AdminAuditLogEntity;
import com.commercefoundry.domainlayer.member.adapter.out.persistence.repository.AdminAuditLogRepository;
import com.commercefoundry.domainlayer.member.application.mapper.AdminAuditLogMapper;
import com.commercefoundry.domainlayer.member.application.model.AdminAuditRecord;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.util.OptionalLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

/** 감사 DB 어댑터의 기록 계약 — 로그 한 줄이 먼저, 행은 REQUIRES_NEW, 저장 실패는 업무로 번지지 않는다. 실제 질의는 {@code AdminAuditLogPersistenceAdapterTest}. */
class AdminAuditLogRepositoryAdapterTest {

    private AdminAuditLogAdapter logAdapter;
    private AdminAuditLogRepository repository;
    private PlatformTransactionManager transactionManager;
    private AdminAuditLogRepositoryAdapter adapter;

    @BeforeEach
    void setUp() {
        logAdapter = mock(AdminAuditLogAdapter.class);
        repository = mock(AdminAuditLogRepository.class);
        transactionManager = mock(PlatformTransactionManager.class);
        given(transactionManager.getTransaction(any())).willReturn(mock(TransactionStatus.class));
        adapter = new AdminAuditLogRepositoryAdapter(
            logAdapter, repository, mock(AdminAuditLogMapper.class), new SnowflakeIdGenerator(1, 1), transactionManager
        );
    }

    @Test
    @DisplayName("로그 한 줄을 먼저 남기고 같은 네 칸으로 행을 넣는다 — 새 트랜잭션(REQUIRES_NEW)이다")
    void writesLogThenRow() {
        adapter.adminLoginFailed(OptionalLong.of(9L));

        InOrder order = inOrder(logAdapter, repository);
        order.verify(logAdapter).write(AdminAuditRecord.adminLoginFailed(OptionalLong.of(9L)));
        ArgumentCaptor<AdminAuditLogEntity> row = ArgumentCaptor.forClass(AdminAuditLogEntity.class);
        order.verify(repository).saveAndFlush(row.capture());
        assertThat(row.getValue().getAction()).isEqualTo("ADMIN_LOGIN");
        assertThat(row.getValue().getActorMemberId()).isNull();
        assertThat(row.getValue().getTarget()).isEqualTo("member:9");
        assertThat(row.getValue().getResult()).isEqualTo("FAILURE");
        assertThat(row.getValue().getId()).isPositive();

        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager).getTransaction(definition.capture());
        assertThat(definition.getValue().getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Test
    @DisplayName("DB 저장이 실패해도 예외를 던지지 않는다 — 감사 실패가 로그인 · 조회 응답을 바꾸지 않고, 로그 한 줄은 이미 남았다")
    void swallowsPersistFailure() {
        given(repository.saveAndFlush(any())).willThrow(new DataAccessResourceFailureException("db down"));

        assertThatCode(() -> adapter.adminLoginSucceeded(9L)).doesNotThrowAnyException();
        verify(logAdapter).write(AdminAuditRecord.adminLoginSucceeded(9L));
    }

    @Test
    @DisplayName("커밋 시점 실패도 삼킨다")
    void swallowsCommitFailure() {
        doThrow(new DataAccessResourceFailureException("commit failed")).when(transactionManager).commit(any());

        assertThatCode(() -> adapter.membersSearched(9L, null, 0, 20)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("행위자도 대상도 없는 로그인 실패(없는 이메일 · 고객 계정)는 로그 한 줄만 남기고 행을 만들지 않는다")
    void skipsRowWithoutActorAndTarget() {
        adapter.adminLoginFailed(OptionalLong.empty());

        verify(logAdapter).write(AdminAuditRecord.adminLoginFailed(OptionalLong.empty()));
        verifyNoInteractions(repository, transactionManager);
    }
}
