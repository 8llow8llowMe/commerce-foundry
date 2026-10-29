package com.commercefoundry.domainlayer.skucost.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SKU · 유형별 원가 타임라인 규칙. 상태가 없는 판정 함수만 둔다.
 *
 * <ul>
 *   <li><b>적용 원가</b> — 시각 {@code at} 의 원가는 유형마다 {@code effectiveFrom <= at} 인 행 중 시작이 가장 늦은 행이다. 그런 행이 없는 유형은 결과에 없다
 *       (미입력 — 0 으로 채우지 않는다).</li>
 *   <li><b>단조</b> — 새 행의 시작은 같은 SKU · 유형의 최신 행 시작보다 반드시 뒤다.</li>
 *   <li><b>소급 금지</b> — 이미 행이 있는 유형은 시작이 지금 이후({@code effectiveFrom >= now})여야 한다. 지난 구간의 원가(그 구간에 난 주문의 원가)를
 *       나중에 몰래 바꾸지 않기 위해서다. 첫 행만 과거 시작을 받는다 — 미입력을 값으로 채울 뿐 이미 알던 원가를 바꾸지 않는다.</li>
 *   <li><b>미래 한도</b> — 시작은 지금 + {@link #FUTURE_TOLERANCE} 까지다. 예약 적용은 이 범위 밖이다 — 어드민 화면 시계 오차만 받는다.</li>
 *   <li><b>통화 고정</b> — 타임라인 통화는 첫 행 통화다. 이후 행은 같은 통화여야 한다. 판매가 통화와 같을 필요는 없다.</li>
 * </ul>
 */
public final class SkuCostTimeline {

    /** 적용 시작 시각이 지금보다 뒤여도 받는 오차. */
    public static final Duration FUTURE_TOLERANCE = Duration.ofMinutes(5);

    private SkuCostTimeline() {
    }

    /**
     * 시각 {@code at} 에 적용되는 유형별 원가. 유형 선언 순서(GOODS → PACKAGING)이고, 적용할 행이 없는 유형은 빠진다.
     * 같은 유형 · 같은 시작이 둘이면(유니크 키가 막지만) 아이디가 큰 행을 고른다.
     */
    public static List<SkuCost> resolveAt(Collection<SkuCost> rows, Instant at) {
        Map<SkuCostType, SkuCost> current = new EnumMap<>(SkuCostType.class);
        Comparator<SkuCost> newerFirst = Comparator.comparing(SkuCost::effectiveFrom).thenComparingLong(SkuCost::id);
        for (SkuCost row : rows) {
            if (row.effectiveFrom().isAfter(at)) {
                continue;
            }
            current.merge(row.costType(), row, (left, right) -> newerFirst.compare(left, right) >= 0 ? left : right);
        }
        return List.copyOf(current.values());
    }

    /**
     * 새 행을 타임라인 끝에 붙일 수 있는지 본다. 위반이 여럿이면 미래 한도 → 통화 → 소급 → 단조 순으로 하나만 돌려준다.
     * 소급과 단조가 함께 걸리는 것은 시작이 지금보다 앞이면서 최신 행 시작 이하일 때다 — 그때는 운영자가 이해하기 쉬운
     * 소급({@link SkuCostAppendViolation#RETROACTIVE_NOT_ALLOWED})을 낸다. 시작이 지금 이후인데 단조에 걸리는 것은 최신 행이 미래 오차(5분) 안에 있을 때뿐이다.
     *
     * @param latest    같은 SKU · 유형의 최신 행. 첫 행이면 null
     * @param candidate 붙일 행
     * @param now       판정 기준 시각. 호출자가 후보 시작과 같은 정밀도(마이크로초)로 잘라 넘긴다
     */
    public static Optional<SkuCostAppendViolation> validateAppend(SkuCost latest, SkuCost candidate, Instant now) {
        if (candidate.effectiveFrom().isAfter(now.plus(FUTURE_TOLERANCE))) {
            return Optional.of(SkuCostAppendViolation.IN_FUTURE);
        }
        if (latest == null) {
            return Optional.empty();
        }
        if (latest.amount().currency() != candidate.amount().currency()) {
            return Optional.of(SkuCostAppendViolation.CURRENCY_MISMATCH);
        }
        if (candidate.effectiveFrom().isBefore(now)) {
            return Optional.of(SkuCostAppendViolation.RETROACTIVE_NOT_ALLOWED);
        }
        if (!candidate.effectiveFrom().isAfter(latest.effectiveFrom())) {
            return Optional.of(SkuCostAppendViolation.NOT_AFTER_LATEST);
        }
        return Optional.empty();
    }
}
