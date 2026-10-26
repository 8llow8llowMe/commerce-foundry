package com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.dto.item;

import com.commercefoundry.common.dto.item.MoneyItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import lombok.Builder;

/**
 * 어드민 국내 배송비 정책 교체 이력 한 건 (#69). {@code adminMemberId} 는 내부 회원 아이디라 그대로 낸다.
 * {@code adminDisplayName} 은 auth-service 가 마스킹해 준 이름이다 (#322). 원문 이름은 받지 않고, 모르면 null 이다.
 */
@Builder
@Schema(description = "어드민 국내 배송비 정책 교체 이력 한 건")
public record ShippingPolicyHistoryAdminItem(

    @Schema(description = "이력 아이디", example = "4611686018427388100")
    String historyId,

    @Schema(description = "교체한 어드민 회원 아이디", example = "4611686018427387904")
    String adminMemberId,

    @Schema(description = "교체한 어드민의 마스킹 이름. 회원을 모르거나 이름이 없으면 null", example = "홍*동", nullable = true)
    String adminDisplayName,

    @Schema(description = "교체 시각 (UTC, ISO-8601)", example = "2026-10-18T03:00:00Z")
    Instant changedAt,

    @Schema(description = "교체 전 정책. 정책을 처음 만든 교체면 null", nullable = true)
    SnapshotItem before,

    @Schema(description = "교체 후 정책. 값이 그대로인 교체(같은 본문 재전송)도 이력이 남고 version 만 오른다")
    SnapshotItem after
) {

    @Builder
    @Schema(description = "그 시점의 국내 배송비 정책 값")
    public record SnapshotItem(

        @Schema(description = "기본 배송비")
        MoneyItem baseFee,

        @Schema(description = "무료배송 기준 주문 금액. null 이면 무료배송 없음", nullable = true)
        MoneyItem freeShippingThreshold,

        @Schema(description = "도서산간 구간. 시작 우편번호 오름차순. 없으면 빈 배열 — 구간 아이디는 싣지 않는다(교체마다 새로 발급돼 이력에서 뜻이 없다)")
        List<RemoteAreaSnapshotItem> remoteAreas,

        @Schema(description = "그 시점의 정책 version", example = "3")
        long version
    ) {
    }

    @Builder
    @Schema(description = "도서산간 우편번호 구간 — 양 끝을 포함한다")
    public record RemoteAreaSnapshotItem(

        @Schema(description = "구간 이름", example = "제주")
        String name,

        @Schema(description = "시작 우편번호 (포함)", example = "63000")
        String postalCodeFrom,

        @Schema(description = "끝 우편번호 (포함)", example = "63644")
        String postalCodeTo,

        @Schema(description = "추가 배송비")
        MoneyItem surcharge
    ) {
    }
}
