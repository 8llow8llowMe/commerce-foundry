package com.commercefoundry.domainlayer.skucost.adapter.out.persistence;

import com.commercefoundry.domainlayer.skucost.adapter.out.persistence.entity.SkuCostEntity;
import com.commercefoundry.domainlayer.skucost.adapter.out.persistence.repository.SkuCostRepository;
import com.commercefoundry.domainlayer.skucost.application.exception.SkuCostErrorCode;
import com.commercefoundry.domainlayer.skucost.application.exception.SkuCostException;
import com.commercefoundry.domainlayer.skucost.application.port.out.SkuCostRepositoryPort;
import com.commercefoundry.domainlayer.skucost.domain.model.SkuCost;
import com.commercefoundry.domainlayer.skucost.domain.model.SkuCostType;
import com.commercefoundry.persistence.util.ConstraintViolations;
import com.commercefoundry.shared.commerce.Money;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * SKU 원가 이력 저장 · 조회. 칸이 적고 변환이 단순해 매퍼 없이 여기서 바꾼다. 유니크 위반은 {@code saveAndFlush} 자리에서 409 로 바꾼다
 * ({@code coding-conventions.md} §9-1) — 커밋 때 터지면 Facade 밖이라 바꿀 자리가 없다.
 */
@Component
@RequiredArgsConstructor
public class SkuCostRepositoryAdapter implements SkuCostRepositoryPort {

    static final String UK_SKU_ID_COST_TYPE_EFFECTIVE_FROM = "uk_sku_cost_sku_id_cost_type_effective_from";

    private final SkuCostRepository skuCostRepository;

    @Override
    public SkuCost insert(SkuCost cost) {
        try {
            return toDomain(skuCostRepository.saveAndFlush(toEntity(cost)));
        } catch (DataIntegrityViolationException exception) {
            if (ConstraintViolations.violates(exception, UK_SKU_ID_COST_TYPE_EFFECTIVE_FROM)) {
                throw new SkuCostException(SkuCostErrorCode.COST_CONFLICT, exception);
            }
            throw exception;
        }
    }

    @Override
    public Optional<SkuCost> findLatest(long skuId, SkuCostType costType) {
        return skuCostRepository.findFirstBySkuIdAndCostTypeOrderByEffectiveFromDesc(skuId, costType).map(this::toDomain);
    }

    @Override
    public List<SkuCost> findCurrent(long skuId, Instant at) {
        return skuCostRepository.findCurrentBySkuId(skuId, at).stream().map(this::toDomain).toList();
    }

    @Override
    public List<SkuCost> findAllUpTo(Collection<Long> skuIds, Instant maxAt) {
        if (skuIds.isEmpty()) {
            return List.of();
        }
        return skuCostRepository.findAllBySkuIdInAndEffectiveFromLessThanEqual(skuIds, maxAt).stream().map(this::toDomain).toList();
    }

    @Override
    public Page<SkuCost> findHistoryPage(long skuId, SkuCostType costType, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size);
        Page<SkuCostEntity> found = costType == null
            ? skuCostRepository.findAllBySkuIdOrderByEffectiveFromDescIdDesc(skuId, pageable)
            : skuCostRepository.findAllBySkuIdAndCostTypeOrderByEffectiveFromDescIdDesc(skuId, costType, pageable);
        return found.map(this::toDomain);
    }

    /** 저장된 금액은 DB check({@code ck_sku_cost_amount_non_negative})가 0 이상을 보장한다. */
    private SkuCost toDomain(SkuCostEntity entity) {
        return new SkuCost(
            entity.getId(),
            entity.getSkuId(),
            entity.getCostType(),
            Money.of(entity.getAmount(), entity.getCurrency()),
            entity.getEffectiveFrom(),
            entity.getReason(),
            entity.getAdminMemberId(),
            entity.getCreatedAt()
        );
    }

    private SkuCostEntity toEntity(SkuCost cost) {
        return SkuCostEntity.builder()
            .id(cost.id())
            .skuId(cost.skuId())
            .costType(cost.costType())
            .amount(cost.amount().amount())
            .currency(cost.amount().currency())
            .effectiveFrom(cost.effectiveFrom())
            .reason(cost.reason())
            .adminMemberId(cost.adminMemberId())
            .build();
    }
}
