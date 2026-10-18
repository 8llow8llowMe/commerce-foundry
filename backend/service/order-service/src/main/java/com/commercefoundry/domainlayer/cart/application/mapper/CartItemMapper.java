package com.commercefoundry.domainlayer.cart.application.mapper;

import com.commercefoundry.domainlayer.cart.adapter.out.persistence.entity.CartItemCustomOptionEntity;
import com.commercefoundry.domainlayer.cart.adapter.out.persistence.entity.CartItemEntity;
import com.commercefoundry.domainlayer.cart.domain.model.CartItem;
import com.commercefoundry.domainlayer.cart.domain.model.CustomOption;
import com.commercefoundry.domainlayer.cart.domain.model.CustomOptions;
import java.util.ArrayList;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface CartItemMapper {

    @Mapping(target = "customOptionHash", expression = "java(cartItem.customOptions().hash())")
    CartItemEntity toEntity(CartItem cartItem);

    @Mapping(target = "value", source = "fieldValue")
    CustomOption toCustomOption(CartItemCustomOptionEntity entity);

    /**
     * @param options 이 줄의 입력 — 표시 순서
     */
    default CartItem toDomain(CartItemEntity entity, List<CartItemCustomOptionEntity> options) {
        CustomOptions customOptions = CustomOptions.of(options.stream().map(this::toCustomOption).toList());
        return new CartItem(entity.getId(), entity.getMemberId(), entity.getSkuId(), entity.getQuantity(), customOptions, entity.getVersion());
    }

    /**
     * 줄의 입력을 행으로 만든다. 표시 순서는 목록 순서다.
     *
     * @throws IllegalStateException 아이디가 없는 입력이 있을 때 — 새 줄은 Processor 가 아이디를 채운 뒤 저장한다
     */
    default List<CartItemCustomOptionEntity> toCustomOptionEntities(CartItem cartItem) {
        List<CustomOption> values = cartItem.customOptions().values();
        List<CartItemCustomOptionEntity> entities = new ArrayList<>(values.size());
        for (int index = 0; index < values.size(); index++) {
            CustomOption option = values.get(index);
            if (option.id() == null) {
                throw new IllegalStateException("맞춤 제작 입력에 아이디가 없습니다: cartItemId=%d, fieldKey=%s".formatted(cartItem.id(), option.fieldKey()));
            }
            entities.add(CartItemCustomOptionEntity.builder()
                .id(option.id())
                .cartItemId(cartItem.id())
                .fieldKey(option.fieldKey())
                .fieldValue(option.value())
                .displayOrder(index)
                .build());
        }
        return entities;
    }
}
