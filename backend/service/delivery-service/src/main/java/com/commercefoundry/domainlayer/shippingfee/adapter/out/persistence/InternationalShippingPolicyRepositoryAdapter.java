package com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence;

import com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.entity.ShippingPolicyEntity;
import com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.entity.ShippingZoneCountryEntity;
import com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.entity.ShippingZoneEntity;
import com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.entity.ShippingZoneRateEntity;
import com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.repository.ShippingPolicyRepository;
import com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.repository.ShippingZoneCountryRepository;
import com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.repository.ShippingZoneRateRepository;
import com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.repository.ShippingZoneRepository;
import com.commercefoundry.domainlayer.shippingfee.application.mapper.InternationalShippingPolicyMapper;
import com.commercefoundry.domainlayer.shippingfee.application.port.out.InternationalShippingPolicyRepositoryPort;
import com.commercefoundry.domainlayer.shippingfee.domain.model.InternationalShippingPolicy;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingScope;
import com.commercefoundry.shared.commerce.Money;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * 해외 배송비 정책 영속성. 정책 행은 국내와 같은 {@code shipping_policy} 테이블의 {@code INTERNATIONAL} 범위 행이고,
 * 생성 충돌 판정 · 조건부 UPDATE 는 국내 어댑터({@link ShippingPolicyRepositoryAdapter})와 같다.
 */
@Component
@RequiredArgsConstructor
public class InternationalShippingPolicyRepositoryAdapter implements InternationalShippingPolicyRepositoryPort {

    private final ShippingPolicyRepository shippingPolicyRepository;
    private final ShippingZoneRepository shippingZoneRepository;
    private final ShippingZoneCountryRepository shippingZoneCountryRepository;
    private final ShippingZoneRateRepository shippingZoneRateRepository;
    private final InternationalShippingPolicyMapper internationalShippingPolicyMapper;

    @Override
    public Optional<InternationalShippingPolicy> findPolicy() {
        return shippingPolicyRepository.findByScope(ShippingScope.INTERNATIONAL).map(this::toDomain);
    }

    /**
     * 정책 행 → 하위 행 삭제(구간 → 국가 → 그룹, 서브쿼리가 그룹을 거치므로 그룹을 마지막에) → 하위 행 삽입(그룹 → 국가 → 구간) → 다시 읽기 순서다.
     * 모든 쓰기를 여기서 flush 해 충돌이 커밋 시점이 아니라 이 호출에서 드러나게 한다.
     */
    @Override
    public InternationalShippingPolicy replace(InternationalShippingPolicy policy) {
        if (policy.version() == null) {
            create(policy);
        } else {
            update(policy);
        }
        shippingZoneRateRepository.deleteAllByShippingPolicyId(policy.id());
        shippingZoneCountryRepository.deleteAllByShippingPolicyId(policy.id());
        shippingZoneRepository.deleteAllByShippingPolicyId(policy.id());
        shippingZoneRepository.saveAllAndFlush(internationalShippingPolicyMapper.toZoneEntities(policy));
        shippingZoneCountryRepository.saveAllAndFlush(internationalShippingPolicyMapper.toCountryEntities(policy));
        shippingZoneRateRepository.saveAllAndFlush(internationalShippingPolicyMapper.toRateEntities(policy));

        ShippingPolicyEntity saved = shippingPolicyRepository.findById(policy.id())
            .orElseThrow(() -> new IllegalStateException("저장한 해외 배송비 정책을 다시 읽지 못했습니다: id=" + policy.id()));
        return toDomain(saved);
    }

    /** 범위 유일 키 · 기본 키 위반만 버전 충돌과 같은 신호로 알린다. check · NOT NULL 위반은 코드 오류라 그대로 던진다. */
    private void create(InternationalShippingPolicy policy) {
        try {
            shippingPolicyRepository.saveAndFlush(internationalShippingPolicyMapper.toEntity(policy));
        } catch (DataIntegrityViolationException exception) {
            if (!ShippingPolicyRepositoryAdapter.isDuplicatePolicy(exception)) {
                throw exception;
            }
            throw new ObjectOptimisticLockingFailureException(ShippingPolicyEntity.class, policy.id(), "shipping policy already created", exception);
        }
    }

    private void update(InternationalShippingPolicy policy) {
        Money threshold = policy.freeShippingThreshold();
        int updated = shippingPolicyRepository.updateIfVersionMatches(
            policy.id(), policy.version(), policy.baseFee().amount(), policy.baseFee().currency(),
            threshold == null ? null : threshold.amount(), threshold == null ? null : threshold.currency(), policy.customsNotice(), Instant.now()
        );
        if (updated == 0) {
            throw new ObjectOptimisticLockingFailureException(ShippingPolicyEntity.class, policy.id());
        }
    }

    /**
     * 하위 행은 테이블마다 한 번씩 읽는다 — 무게 구간은 그룹 아이디 {@code in} 한 번이다(그룹마다 읽지 않는다).
     *
     * <p>네 문장이 같은 스냅숏이 아니면(READ COMMITTED 의 쓰기 트랜잭션이 동시 교체와 겹침) 교체가 새로 발급한 그룹과 옛 국가 · 구간이 섞여 읽힌다.
     * 그룹 아이디 집합과 국가 · 구간이 가리키는 그룹 아이디 집합이 다르면 그 상태다 — 도메인 생성자의 {@link IllegalArgumentException}(500)까지 가지 않고
     * 낙관적 락 실패로 알린다(교체 유스케이스가 006 으로 바꾼다). 조회 유스케이스는 REPEATABLE READ 라 이 상태를 보지 않는다.
     */
    private InternationalShippingPolicy toDomain(ShippingPolicyEntity entity) {
        List<ShippingZoneEntity> zones = shippingZoneRepository.findAllByShippingPolicyIdOrderByDisplayOrderAsc(entity.getId());
        List<Long> zoneIds = zones.stream().map(ShippingZoneEntity::getId).toList();
        List<ShippingZoneCountryEntity> countries = shippingZoneCountryRepository.findAllByShippingPolicyId(entity.getId());
        List<ShippingZoneRateEntity> rates = zoneIds.isEmpty() ? List.of() : shippingZoneRateRepository.findAllByShippingZoneIdIn(zoneIds);
        requireConsistentSnapshot(entity.getId(), zoneIds, countries, rates);
        return internationalShippingPolicyMapper.toDomain(entity, zones, countries, rates);
    }

    /** 그룹마다 국가 · 구간이 1개 이상이라 일관된 스냅숏이면 세 집합이 같다. */
    private static void requireConsistentSnapshot(
        long policyId, List<Long> zoneIds, List<ShippingZoneCountryEntity> countries, List<ShippingZoneRateEntity> rates
    ) {
        Set<Long> zones = Set.copyOf(zoneIds);
        Set<Long> countryZones = countries.stream().map(ShippingZoneCountryEntity::getShippingZoneId).collect(Collectors.toSet());
        Set<Long> rateZones = rates.stream().map(ShippingZoneRateEntity::getShippingZoneId).collect(Collectors.toSet());
        if (!zones.equals(countryZones) || !zones.equals(rateZones)) {
            throw new ObjectOptimisticLockingFailureException(ShippingPolicyEntity.class, policyId);
        }
    }
}
