package com.commercefoundry.domainlayer.shippingaddress.application.mapper;

import com.commercefoundry.domainlayer.shippingaddress.adapter.out.persistence.entity.ShippingAddressEntity;
import com.commercefoundry.domainlayer.shippingaddress.domain.model.ShippingAddress;
import com.commercefoundry.domainlayer.shippingaddress.domain.model.ShippingAddressContent;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;

/**
 * 엔티티의 평평한 칸 ↔ 도메인의 내용 묶음 · 기본 여부. 기본 여부는 {@code default_member_id} 로 옮긴다 — 기본이면 회원 아이디, 아니면 null.
 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface ShippingAddressMapper {

    /** {@link ShippingAddressContent#restore} 로 검증 없이 되살린다 — 규칙 밖으로 저장된 행(이관 데이터)도 목록 조회가 실패하지 않게. */
    default ShippingAddress toDomain(ShippingAddressEntity entity) {
        ShippingAddressContent content = ShippingAddressContent.restore(
            entity.getLabel(), entity.getRecipientName(), entity.getRecipientPhone(), entity.getCountryCode(), entity.getPostalCode(),
            entity.getAddressLine1(), entity.getAddressLine2(), entity.getCity(), entity.getStateProvince()
        );
        return ShippingAddress.restore(entity.getId(), entity.getMemberId(), content, entity.getDefaultMemberId() != null, entity.getVersion());
    }

    /** 버전을 그대로 옮긴다 — null 이면 새 주소(persist), 값이 있으면 그 버전에 대한 수정(merge 후 버전 비교)이다. */
    default ShippingAddressEntity toEntity(ShippingAddress address) {
        ShippingAddressContent content = address.content();
        return ShippingAddressEntity.builder()
            .id(address.id())
            .memberId(address.memberId())
            .label(content.label())
            .recipientName(content.recipientName())
            .recipientPhone(content.recipientPhone())
            .countryCode(content.countryCode())
            .postalCode(content.postalCode())
            .addressLine1(content.addressLine1())
            .addressLine2(content.addressLine2())
            .city(content.city())
            .stateProvince(content.stateProvince())
            .defaultMemberId(address.defaultAddress() ? address.memberId() : null)
            .version(address.version())
            .build();
    }
}
