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
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingScope;
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

    @ParameterizedTest
    @ValueSource(strings = {"uk_shipping_policy_scope", "shipping_policy_pkey", "UK_SHIPPING_POLICY_SCOPE_INDEX_1"})
    @DisplayName("범위 유일 키 · 기본 키 위반은 낙관적 락 실패로 바꾼다 — 구간은 건드리지 않는다")
    void duplicateIsConflict(String constraintName) {
        given(shippingPolicyRepository.saveAndFlush(any())).willThrow(violation(constraintName));

        assertThatThrownBy(() -> adapter.replace(NEW_POLICY)).isInstanceOf(ObjectOptimisticLockingFailureException.class);
        verifyNoInteractions(shippingRemoteAreaRepository);
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
