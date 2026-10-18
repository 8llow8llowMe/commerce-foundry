package com.commercefoundry.domainlayer.shippingfee.application.mapper;

import com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.entity.ShippingPolicyEntity;
import com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.entity.ShippingRemoteAreaEntity;
import com.commercefoundry.domainlayer.shippingfee.domain.model.PostalCode;
import com.commercefoundry.domainlayer.shippingfee.domain.model.RemoteArea;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingPolicy;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/**
 * 배송비 정책 Entity ↔ Domain. 금액 컬럼 쌍은 {@link MoneyColumns} 로만 묶는다.
 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ShippingPolicyMapper {

    @Mapping(target = "id", source = "policy.id")
    @Mapping(target = "scope", source = "policy.scope")
    @Mapping(target = "baseFee", expression = "java(MoneyColumns.required(policy.getBaseFeeAmount(), policy.getBaseFeeCurrency()))")
    @Mapping(
        target = "freeShippingThreshold",
        expression = "java(MoneyColumns.nullable(policy.getFreeShippingThresholdAmount(), policy.getFreeShippingThresholdCurrency()))"
    )
    @Mapping(target = "remoteAreas", source = "remoteAreas")
    @Mapping(target = "version", source = "policy.version")
    @Mapping(target = "updatedAt", source = "policy.updatedAt")
    ShippingPolicy toDomain(ShippingPolicyEntity policy, List<ShippingRemoteAreaEntity> remoteAreas);

    @Mapping(target = "surcharge", expression = "java(MoneyColumns.required(entity.getSurchargeAmount(), entity.getSurchargeCurrency()))")
    RemoteArea toDomain(ShippingRemoteAreaEntity entity);

    /** 새로 만들 정책만 엔티티로 바꾼다 — 버전이 null 이라 Spring Data 가 {@code persist} 한다. 기존 정책은 조건부 UPDATE 로 바꾼다. */
    @Mapping(target = "baseFeeAmount", source = "baseFee.amount")
    @Mapping(target = "baseFeeCurrency", source = "baseFee.currency")
    @Mapping(target = "freeShippingThresholdAmount", source = "freeShippingThreshold.amount")
    @Mapping(target = "freeShippingThresholdCurrency", source = "freeShippingThreshold.currency")
    @Mapping(target = "customsNotice", ignore = true)
    ShippingPolicyEntity toEntity(ShippingPolicy policy);

    @Mapping(target = "id", source = "remoteArea.id")
    @Mapping(target = "shippingPolicyId", source = "shippingPolicyId")
    @Mapping(target = "name", source = "remoteArea.name")
    @Mapping(target = "postalCodeFrom", source = "remoteArea.postalCodeFrom")
    @Mapping(target = "postalCodeTo", source = "remoteArea.postalCodeTo")
    @Mapping(target = "surchargeAmount", source = "remoteArea.surcharge.amount")
    @Mapping(target = "surchargeCurrency", source = "remoteArea.surcharge.currency")
    ShippingRemoteAreaEntity toEntity(RemoteArea remoteArea, Long shippingPolicyId);

    default List<ShippingRemoteAreaEntity> toEntities(List<RemoteArea> remoteAreas, long shippingPolicyId) {
        return remoteAreas.stream().map(remoteArea -> toEntity(remoteArea, shippingPolicyId)).toList();
    }

    default PostalCode toPostalCode(String value) {
        return new PostalCode(value);
    }

    default String toValue(PostalCode postalCode) {
        return postalCode.value();
    }
}
