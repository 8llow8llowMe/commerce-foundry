package com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence;

import com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.entity.ShippingPolicyEntity;
import com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.repository.ShippingPolicyRepository;
import com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.repository.ShippingRemoteAreaRepository;
import com.commercefoundry.domainlayer.shippingfee.application.mapper.ShippingPolicyMapper;
import com.commercefoundry.domainlayer.shippingfee.application.port.out.ShippingPolicyRepositoryPort;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingPolicy;
import com.commercefoundry.persistence.util.ConstraintViolations;
import com.commercefoundry.shared.commerce.Money;
import com.commercefoundry.shared.commerce.ShippingScope;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ShippingPolicyRepositoryAdapter implements ShippingPolicyRepositoryPort {

    /** 동시 최초 생성이 부딪히는 제약 — 범위 유일 키와 PostgreSQL 기본 키({@code <테이블>_pkey}). 소문자 · 스키마 없는 이름이다. */
    private static final Set<String> DUPLICATE_POLICY_CONSTRAINTS = Set.of("uk_shipping_policy_scope", "shipping_policy_pkey");

    private final ShippingPolicyRepository shippingPolicyRepository;
    private final ShippingRemoteAreaRepository shippingRemoteAreaRepository;
    private final ShippingPolicyMapper shippingPolicyMapper;

    @Override
    public Optional<ShippingPolicy> findDomesticPolicy() {
        return shippingPolicyRepository.findByScope(ShippingScope.DOMESTIC).map(this::toDomain);
    }

    /** 범위 유일 키({@code uk_shipping_policy_scope})를 타는 존재 확인 한 문장이다. */
    @Override
    public boolean existsDomesticPolicy() {
        return shippingPolicyRepository.existsByScope(ShippingScope.DOMESTIC);
    }

    /**
     * 정책 행 → 구간 삭제 → 구간 삽입 → 다시 읽기 순서다. 모든 쓰기를 여기서 flush 해 충돌이 커밋 시점이 아니라 이 호출에서 드러나게 한다
     * (Processor 가 충돌을 도메인 오류로 바꿀 수 있게).
     */
    @Override
    public ShippingPolicy replace(ShippingPolicy policy) {
        if (policy.version() == null) {
            create(policy);
        } else {
            update(policy);
        }
        shippingRemoteAreaRepository.deleteAllByShippingPolicyId(policy.id());
        shippingRemoteAreaRepository.saveAllAndFlush(shippingPolicyMapper.toEntities(policy.remoteAreas(), policy.id()));

        ShippingPolicyEntity saved = shippingPolicyRepository.findById(policy.id())
            .orElseThrow(() -> new IllegalStateException("저장한 배송비 정책을 다시 읽지 못했습니다: id=" + policy.id()));
        return toDomain(saved);
    }

    /**
     * 범위 유일 키({@code uk_shipping_policy_scope})가 동시 최초 생성 중 하나만 통과시킨다. 그 유일 키 · 기본 키 위반만 버전 충돌과 같은 신호로 알린다.
     * check · NOT NULL 위반은 코드 오류라 그대로 던진다 — 409 로 바꾸면 버그가 "다시 시도하세요" 뒤에 숨는다.
     */
    private void create(ShippingPolicy policy) {
        try {
            shippingPolicyRepository.saveAndFlush(shippingPolicyMapper.toEntity(policy));
        } catch (DataIntegrityViolationException exception) {
            if (!isDuplicatePolicy(exception)) {
                throw exception;
            }
            throw new ObjectOptimisticLockingFailureException(ShippingPolicyEntity.class, policy.id(), "shipping policy already created", exception);
        }
    }

    /**
     * 위반한 제약 이름을 persistence-core {@link ConstraintViolations} 로 정하고(PostgreSQL 서버 오류 필드 우선 — {@code lc_messages} 로케일과 무관) 정확히 비교한다.
     * 이름을 모르면 충돌로 보지 않는다 ({@code coding-conventions.md} §9-1, #247 · #159).
     * 해외 정책 어댑터({@link InternationalShippingPolicyRepositoryAdapter})도 같은 정책 행을 만들므로 같은 판정을 쓴다.
     */
    static boolean isDuplicatePolicy(DataIntegrityViolationException exception) {
        String constraintName = ConstraintViolations.constraintNameOf(exception);
        return constraintName != null && DUPLICATE_POLICY_CONSTRAINTS.contains(constraintName);
    }

    private void update(ShippingPolicy policy) {
        Money threshold = policy.freeShippingThreshold();
        int updated = shippingPolicyRepository.updateIfVersionMatches(
            policy.id(), policy.version(), policy.baseFee().amount(), policy.baseFee().currency(),
            threshold == null ? null : threshold.amount(), threshold == null ? null : threshold.currency(), null, Instant.now()
        );
        if (updated == 0) {
            throw new ObjectOptimisticLockingFailureException(ShippingPolicyEntity.class, policy.id());
        }
    }

    private ShippingPolicy toDomain(ShippingPolicyEntity entity) {
        return shippingPolicyMapper.toDomain(entity, shippingRemoteAreaRepository.findAllByShippingPolicyIdOrderByPostalCodeFromAsc(entity.getId()));
    }
}
