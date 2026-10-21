package com.commercefoundry.domainlayer.shippingfee.application.mapper;

import com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.entity.ShippingPolicyEntity;
import com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.entity.ShippingZoneCountryEntity;
import com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.entity.ShippingZoneEntity;
import com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.entity.ShippingZoneRateEntity;
import com.commercefoundry.domainlayer.shippingfee.domain.model.InternationalShippingPolicy;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingZone;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingZoneCountry;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingZoneRate;
import com.commercefoundry.shared.commerce.ShippingScope;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/**
 * 해외 배송비 정책 Entity ↔ Domain. 행 하나짜리 변환은 MapStruct 가 만들고, 평평한 하위 행을 국가 그룹으로 묶는 조립은 default 메서드가 한다.
 * 금액 컬럼 쌍은 {@link MoneyColumns} 로만 묶는다.
 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR, imports = ShippingScope.class)
public interface InternationalShippingPolicyMapper {

    /**
     * 정책 행 + 하위 행 → 도메인. 국가 그룹은 표시 순서로 놓고, 국가 · 무게 구간은 그룹 아이디로 묶는다(정렬은 도메인이 한다).
     *
     * @throws IllegalStateException 해외 범위 행이 아니거나 저장된 금액이 올바르지 않을 때 — 데이터 · 코드 오류다
     */
    default InternationalShippingPolicy toDomain(
        ShippingPolicyEntity policy, List<ShippingZoneEntity> zones, List<ShippingZoneCountryEntity> countries, List<ShippingZoneRateEntity> rates
    ) {
        if (policy.getScope() != ShippingScope.INTERNATIONAL) {
            throw new IllegalStateException("해외 배송비 정책 행이 아닙니다: id=%d, scope=%s".formatted(policy.getId(), policy.getScope()));
        }
        Map<Long, List<ShippingZoneCountry>> countriesByZone = countries.stream()
            .collect(Collectors.groupingBy(ShippingZoneCountryEntity::getShippingZoneId, Collectors.mapping(this::toDomain, Collectors.toList())));
        Map<Long, List<ShippingZoneRate>> ratesByZone = rates.stream()
            .collect(Collectors.groupingBy(ShippingZoneRateEntity::getShippingZoneId, Collectors.mapping(this::toDomain, Collectors.toList())));
        List<ShippingZone> domainZones = zones.stream()
            .sorted(Comparator.comparingInt(ShippingZoneEntity::getDisplayOrder))
            .map(zone -> new ShippingZone(
                zone.getId(), zone.getName(), countriesByZone.getOrDefault(zone.getId(), List.of()), ratesByZone.getOrDefault(zone.getId(), List.of())
            ))
            .toList();
        return new InternationalShippingPolicy(
            policy.getId(),
            MoneyColumns.required(policy.getBaseFeeAmount(), policy.getBaseFeeCurrency()),
            MoneyColumns.nullable(policy.getFreeShippingThresholdAmount(), policy.getFreeShippingThresholdCurrency()),
            policy.getCustomsNotice(), domainZones, policy.getVersion(), policy.getUpdatedAt()
        );
    }

    ShippingZoneCountry toDomain(ShippingZoneCountryEntity entity);

    @Mapping(target = "fee", expression = "java(MoneyColumns.required(entity.getFeeAmount(), entity.getFeeCurrency()))")
    ShippingZoneRate toDomain(ShippingZoneRateEntity entity);

    /** 새로 만들 정책만 엔티티로 바꾼다 — 버전이 null 이라 Spring Data 가 {@code persist} 한다. 기존 정책은 조건부 UPDATE 로 바꾼다. */
    @Mapping(target = "scope", expression = "java(ShippingScope.INTERNATIONAL)")
    @Mapping(target = "baseFeeAmount", source = "baseFee.amount")
    @Mapping(target = "baseFeeCurrency", source = "baseFee.currency")
    @Mapping(target = "freeShippingThresholdAmount", source = "freeShippingThreshold.amount")
    @Mapping(target = "freeShippingThresholdCurrency", source = "freeShippingThreshold.currency")
    ShippingPolicyEntity toEntity(InternationalShippingPolicy policy);

    @Mapping(target = "id", source = "zone.id")
    @Mapping(target = "shippingPolicyId", source = "shippingPolicyId")
    @Mapping(target = "name", source = "zone.name")
    @Mapping(target = "displayOrder", source = "displayOrder")
    ShippingZoneEntity toEntity(ShippingZone zone, Long shippingPolicyId, Integer displayOrder);

    @Mapping(target = "id", source = "country.id")
    @Mapping(target = "shippingPolicyId", source = "shippingPolicyId")
    @Mapping(target = "shippingZoneId", source = "shippingZoneId")
    @Mapping(target = "countryCode", source = "country.countryCode")
    ShippingZoneCountryEntity toEntity(ShippingZoneCountry country, Long shippingPolicyId, Long shippingZoneId);

    @Mapping(target = "id", source = "rate.id")
    @Mapping(target = "shippingZoneId", source = "shippingZoneId")
    @Mapping(target = "maxWeightGrams", source = "rate.maxWeightGrams")
    @Mapping(target = "feeAmount", source = "rate.fee.amount")
    @Mapping(target = "feeCurrency", source = "rate.fee.currency")
    ShippingZoneRateEntity toEntity(ShippingZoneRate rate, Long shippingZoneId);

    /** 표시 순서는 정책의 그룹 목록 순서(0 부터)다. */
    default List<ShippingZoneEntity> toZoneEntities(InternationalShippingPolicy policy) {
        List<ShippingZone> zones = policy.zones();
        return IntStream.range(0, zones.size()).mapToObj(index -> toEntity(zones.get(index), policy.id(), index)).toList();
    }

    default List<ShippingZoneCountryEntity> toCountryEntities(InternationalShippingPolicy policy) {
        List<ShippingZoneCountryEntity> entities = new ArrayList<>();
        policy.zones().forEach(zone -> zone.countries().forEach(country -> entities.add(toEntity(country, policy.id(), zone.id()))));
        return entities;
    }

    default List<ShippingZoneRateEntity> toRateEntities(InternationalShippingPolicy policy) {
        List<ShippingZoneRateEntity> entities = new ArrayList<>();
        policy.zones().forEach(zone -> zone.rates().forEach(rate -> entities.add(toEntity(rate, zone.id()))));
        return entities;
    }
}
