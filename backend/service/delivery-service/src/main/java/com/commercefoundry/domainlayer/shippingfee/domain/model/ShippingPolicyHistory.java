package com.commercefoundry.domainlayer.shippingfee.domain.model;

import com.commercefoundry.shared.commerce.Money;
import com.commercefoundry.shared.commerce.ShippingScope;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 배송비 정책 교체 이력 한 건 — 누가 · 언제 · 무엇을(교체 전 · 후 값) 바꿨는지. append-only 다 (#69).
 *
 * <p>지금은 국내 정책만 담는다. 저장 테이블은 범위({@code scope}) 칸을 가져 해외 정책 이력도 같은 테이블에 넣을 수 있게 했지만,
 * 해외 정책의 국가 그룹 · 무게 구간 스냅숏은 아직 없다 — 해외를 더할 때 범위별 스냅숏을 함께 설계한다.
 *
 * @param before 교체 전 정책. 정책을 처음 만든 교체면 null
 * @param after 교체 후(저장된) 정책
 * @param adminMemberId 교체한 어드민 회원 아이디 (access 토큰의 memberId)
 * @param changedAt 교체 시각. 아직 저장하지 않은 이력이면 null — 저장 때 감사 리스너가 채운다
 */
public record ShippingPolicyHistory(
    long id, long shippingPolicyId, ShippingScope scope, long adminMemberId, Snapshot before, Snapshot after, Instant changedAt
) {

    public ShippingPolicyHistory {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(after, "after");
        if (scope != ShippingScope.DOMESTIC) {
            throw new IllegalArgumentException("배송비 정책 이력은 아직 국내 범위만 담습니다: " + scope);
        }
        if (before != null && before.version() >= after.version()) {
            throw new IllegalArgumentException("교체 후 버전이 교체 전보다 커야 합니다: before=%d, after=%d".formatted(before.version(), after.version()));
        }
    }

    /**
     * 그 시점의 국내 정책 값. 도서산간 구간의 아이디는 이력 행의 아이디다 — 정책의 구간 아이디는 교체마다 새로 발급돼 이력에서 뜻이 없다.
     *
     * @param freeShippingThreshold null 이면 무료배송 없음
     * @param remoteAreas 시작 우편번호 오름차순으로 정렬해 들고 있는다
     * @param version 그 시점의 정책 버전
     */
    public record Snapshot(Money baseFee, Money freeShippingThreshold, List<RemoteArea> remoteAreas, long version) {

        public Snapshot {
            Objects.requireNonNull(baseFee, "baseFee");
            Objects.requireNonNull(remoteAreas, "remoteAreas");
            remoteAreas = remoteAreas.stream().sorted(Comparator.comparing(RemoteArea::postalCodeFrom)).toList();
        }
    }
}
