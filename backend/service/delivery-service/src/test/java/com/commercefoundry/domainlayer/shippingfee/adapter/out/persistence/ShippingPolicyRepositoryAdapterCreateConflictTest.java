package com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence;

import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.krw;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.repository.ShippingPolicyRepository;
import com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.repository.ShippingRemoteAreaRepository;
import com.commercefoundry.domainlayer.shippingfee.application.mapper.ShippingPolicyMapperImpl;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingPolicy;
import com.commercefoundry.shared.commerce.ShippingScope;
import java.sql.BatchUpdateException;
import java.sql.SQLException;
import java.util.List;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * 새 정책 저장의 무결성 위반 중 "같은 범위 정책이 이미 있다" 만 낙관적 락 실패(→ 006)로 바꾸는지 본다. 실제 PostgreSQL 위반은 {@link ShippingPolicyRepositoryAdapterTest} 가 본다.
 */
@ExtendWith(MockitoExtension.class)
class ShippingPolicyRepositoryAdapterCreateConflictTest {

    private static final ShippingPolicy NEW_POLICY = new ShippingPolicy(7001L, ShippingScope.DOMESTIC, krw(3_000), null, List.of(), null, null);

    @Mock
    private ShippingPolicyRepository shippingPolicyRepository;

    @Mock
    private ShippingRemoteAreaRepository shippingRemoteAreaRepository;

    private ShippingPolicyRepositoryAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new ShippingPolicyRepositoryAdapter(shippingPolicyRepository, shippingRemoteAreaRepository, new ShippingPolicyMapperImpl());
    }

    private static DataIntegrityViolationException violation(String constraintName) {
        return new DataIntegrityViolationException("violation", new ConstraintViolationException("violation", new SQLException("violation"), constraintName));
    }

    /** PostgreSQL 서버 오류 필드 — 'M' 메시지는 lc_messages 로케일을 따르고, 'n' 제약 이름은 로케일과 무관하다. Hibernate 는 메시지에서 이름을 못 뽑아 null 이다. */
    private static DataIntegrityViolationException localizedServerViolation(String constraintName) {
        PSQLException server = new PSQLException(new ServerErrorMessage(
            "SERROR\0C23505\0M중복된 키 값이 고유 제약 조건을 위반함\0n" + constraintName + "\0"));
        return new DataIntegrityViolationException("violation", new ConstraintViolationException("violation", server, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"uk_shipping_policy_scope", "shipping_policy_pkey", "delivery.\"UK_SHIPPING_POLICY_SCOPE\""})
    @DisplayName("범위 유일 키 · 기본 키 위반은 낙관적 락 실패로 바꾼다 — 대소문자 · 스키마 접두사 · 따옴표는 무시하고, 구간은 건드리지 않는다")
    void duplicateIsConflict(String constraintName) {
        given(shippingPolicyRepository.saveAndFlush(any())).willThrow(violation(constraintName));

        assertThatThrownBy(() -> adapter.replace(NEW_POLICY)).isInstanceOf(ObjectOptimisticLockingFailureException.class);
        verifyNoInteractions(shippingRemoteAreaRepository);
    }

    @Test
    @DisplayName("서버 메시지가 한국어(lc_messages)라 Hibernate 가 이름을 못 뽑아도 서버 필드의 제약 이름으로 충돌을 가린다 (#159)")
    void localizedMessageStillConflict() {
        given(shippingPolicyRepository.saveAndFlush(any())).willThrow(localizedServerViolation("uk_shipping_policy_scope"));

        assertThatThrownBy(() -> adapter.replace(NEW_POLICY)).isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    /**
     * delivery 는 JDBC 배치를 켠다(#255) — 한 건 저장도 executeBatch 로 나가 pgjdbc 가 {@code BatchUpdateException} 을 던지고, 서버 예외({@code PSQLException})를
     * 원인으로 잇는다(42.7.13 BatchResultHandler). 그 모양에서도 서버 필드의 이름을 읽는다.
     */
    @Test
    @DisplayName("배치 예외(BatchUpdateException) 안의 서버 오류도 읽는다 — 배치로 나간 동시 최초 생성도 충돌이다")
    void batchUpdateExceptionStillConflict() {
        PSQLException server = new PSQLException(new ServerErrorMessage("SERROR\0C23505\0M중복된 키 값\0nuk_shipping_policy_scope\0"));
        BatchUpdateException batch = new BatchUpdateException("Batch entry 0 was aborted", "23505", 0, new long[0], server);
        given(shippingPolicyRepository.saveAndFlush(any()))
            .willThrow(new DataIntegrityViolationException("could not execute batch", new ConstraintViolationException("could not execute batch", batch, null)));

        assertThatThrownBy(() -> adapter.replace(NEW_POLICY)).isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"uk_shipping_policy_scope_old", "shipping_policy_history_pkey", "UK_SHIPPING_POLICY_SCOPE_INDEX_1"})
    @DisplayName("이름이 정확히 같아야 한다 — 앞부분만 같은 다른 제약 · H2 인덱스 접미사는 충돌로 보지 않고 그대로 던진다")
    void similarNameIsRethrown(String constraintName) {
        DataIntegrityViolationException other = violation(constraintName);
        given(shippingPolicyRepository.saveAndFlush(any())).willThrow(other);

        assertThatThrownBy(() -> adapter.replace(NEW_POLICY)).isSameAs(other);
    }

    @Test
    @DisplayName("check 제약 위반은 그대로 던진다 — 409 로 숨기지 않는다")
    void checkViolationIsRethrown() {
        DataIntegrityViolationException checkViolation = violation("ck_shipping_policy_base_fee_non_negative");
        given(shippingPolicyRepository.saveAndFlush(any())).willThrow(checkViolation);

        assertThatThrownBy(() -> adapter.replace(NEW_POLICY)).isSameAs(checkViolation);
    }

    @Test
    @DisplayName("제약 이름을 알 수 없는 위반(NOT NULL 등)도 그대로 던진다")
    void unnamedViolationIsRethrown() {
        DataIntegrityViolationException notNull = new DataIntegrityViolationException("not null", new SQLException("null value in column"));
        given(shippingPolicyRepository.saveAndFlush(any())).willThrow(notNull);

        assertThatThrownBy(() -> adapter.replace(NEW_POLICY)).isSameAs(notNull);
    }
}
