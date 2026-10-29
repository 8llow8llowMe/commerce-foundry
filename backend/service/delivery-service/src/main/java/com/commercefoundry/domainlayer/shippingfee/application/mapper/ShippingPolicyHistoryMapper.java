package com.commercefoundry.domainlayer.shippingfee.application.mapper;

import com.commercefoundry.commerce.support.persistence.MoneyColumns;
import com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.entity.ShippingPolicyHistoryAreaEntity;
import com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.entity.ShippingPolicyHistoryAreaEntity.Side;
import com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.entity.ShippingPolicyHistoryEntity;
import com.commercefoundry.domainlayer.shippingfee.domain.model.PostalCode;
import com.commercefoundry.domainlayer.shippingfee.domain.model.RemoteArea;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingPolicyHistory;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingPolicyHistory.Snapshot;
import com.commercefoundry.shared.commerce.Money;
import java.util.List;
import java.util.stream.Stream;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/**
 * 배송비 정책 이력 Entity ↔ Domain. 구간 행 하나짜리 변환은 MapStruct 가 만들고, 교체 전 · 후 칸 묶기와 구간을 쪽(BEFORE · AFTER)으로 가르는 조립은
 * default 메서드가 한다. 금액 컬럼 쌍은 {@link MoneyColumns} 로만 묶는다.
 */
@Mapper(imports = MoneyColumns.class, componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ShippingPolicyHistoryMapper {

    /** 교체 시각은 INSERT 때 감사 리스너가 채운다. */
    default ShippingPolicyHistoryEntity toEntity(ShippingPolicyHistory history) {
        Snapshot before = history.before();
        Snapshot after = history.after();
        Money beforeThreshold = before == null ? null : before.freeShippingThreshold();
        Money afterThreshold = after.freeShippingThreshold();
        return ShippingPolicyHistoryEntity.builder()
            .id(history.id())
            .shippingPolicyId(history.shippingPolicyId())
            .scope(history.scope())
            .adminMemberId(history.adminMemberId())
            .beforeVersion(before == null ? null : before.version())
            .beforeBaseFeeAmount(before == null ? null : before.baseFee().amount())
            .beforeBaseFeeCurrency(before == null ? null : before.baseFee().currency())
            .beforeFreeShippingThresholdAmount(beforeThreshold == null ? null : beforeThreshold.amount())
            .beforeFreeShippingThresholdCurrency(beforeThreshold == null ? null : beforeThreshold.currency())
            .afterVersion(after.version())
            .afterBaseFeeAmount(after.baseFee().amount())
            .afterBaseFeeCurrency(after.baseFee().currency())
            .afterFreeShippingThresholdAmount(afterThreshold == null ? null : afterThreshold.amount())
            .afterFreeShippingThresholdCurrency(afterThreshold == null ? null : afterThreshold.currency())
            .build();
    }

    /** 교체 전(있으면) · 후 구간을 한 목록으로 펼친다. */
    default List<ShippingPolicyHistoryAreaEntity> toAreaEntities(ShippingPolicyHistory history) {
        Stream<ShippingPolicyHistoryAreaEntity> before = history.before() == null ? Stream.empty()
            : history.before().remoteAreas().stream().map(area -> toAreaEntity(area, history.id(), Side.BEFORE));
        Stream<ShippingPolicyHistoryAreaEntity> after = history.after().remoteAreas().stream().map(area -> toAreaEntity(area, history.id(), Side.AFTER));
        return Stream.concat(before, after).toList();
    }

    /**
     * @param areas 이 이력의 구간 행 (쪽 · 순서 무관)
     */
    default ShippingPolicyHistory toDomain(ShippingPolicyHistoryEntity entity, List<ShippingPolicyHistoryAreaEntity> areas) {
        Snapshot before = entity.getBeforeVersion() == null ? null : new Snapshot(
            MoneyColumns.required(entity.getBeforeBaseFeeAmount(), entity.getBeforeBaseFeeCurrency()),
            MoneyColumns.nullable(entity.getBeforeFreeShippingThresholdAmount(), entity.getBeforeFreeShippingThresholdCurrency()),
            areasOf(areas, Side.BEFORE),
            entity.getBeforeVersion()
        );
        Snapshot after = new Snapshot(
            MoneyColumns.required(entity.getAfterBaseFeeAmount(), entity.getAfterBaseFeeCurrency()),
            MoneyColumns.nullable(entity.getAfterFreeShippingThresholdAmount(), entity.getAfterFreeShippingThresholdCurrency()),
            areasOf(areas, Side.AFTER),
            entity.getAfterVersion()
        );
        return new ShippingPolicyHistory(
            entity.getId(), entity.getShippingPolicyId(), entity.getScope(), entity.getAdminMemberId(), before, after, entity.getCreatedAt()
        );
    }

    default List<RemoteArea> areasOf(List<ShippingPolicyHistoryAreaEntity> areas, Side side) {
        return areas.stream().filter(area -> area.getSnapshotSide() == side).map(this::toRemoteArea).toList();
    }

    @Mapping(target = "id", source = "remoteArea.id")
    @Mapping(target = "shippingPolicyHistoryId", source = "shippingPolicyHistoryId")
    @Mapping(target = "snapshotSide", source = "snapshotSide")
    @Mapping(target = "name", source = "remoteArea.name")
    @Mapping(target = "postalCodeFrom", source = "remoteArea.postalCodeFrom")
    @Mapping(target = "postalCodeTo", source = "remoteArea.postalCodeTo")
    @Mapping(target = "surchargeAmount", source = "remoteArea.surcharge.amount")
    @Mapping(target = "surchargeCurrency", source = "remoteArea.surcharge.currency")
    ShippingPolicyHistoryAreaEntity toAreaEntity(RemoteArea remoteArea, Long shippingPolicyHistoryId, Side snapshotSide);

    @Mapping(target = "surcharge", expression = "java(MoneyColumns.required(entity.getSurchargeAmount(), entity.getSurchargeCurrency()))")
    RemoteArea toRemoteArea(ShippingPolicyHistoryAreaEntity entity);

    default PostalCode toPostalCode(String value) {
        return new PostalCode(value);
    }

    default String toValue(PostalCode postalCode) {
        return postalCode.value();
    }
}
