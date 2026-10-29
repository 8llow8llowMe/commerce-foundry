package com.commercefoundry.domainlayer.skucost.application.command;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 서비스 간 시점 원가 조회 — (SKU, 시각) 쌍 목록. 만들 때 정규화한다.
 *
 * <ul>
 *   <li>시각은 마이크로초로 자른다 — 저장 · 현재 원가 조회와 같은 정밀도로 비교해야 같은 마이크로초에 시작한 행을 놓치지 않는다
 *       ({@code SkuCostQueryProcessor#getCurrent}).</li>
 *   <li>자른 뒤 같은 (SKU, 시각) 쌍은 하나로 합치고, 처음 나온 순서를 지킨다 — 응답 순서다.</li>
 * </ul>
 *
 * @param points 정규화한 쌍. 비어 있지 않다(요청 검증이 1~100개를 보장한다)
 */
public record SkuCostAtQueryCommand(List<Point> points) {

    public SkuCostAtQueryCommand {
        Objects.requireNonNull(points, "points");
        points = List.copyOf(new LinkedHashSet<>(points.stream().map(Point::truncatedToMicros).toList()));
    }

    /** 조회할 SKU 아이디 — 처음 나온 순서, 중복 없음. */
    public Set<Long> skuIds() {
        Set<Long> skuIds = new LinkedHashSet<>();
        points.forEach(point -> skuIds.add(point.skuId()));
        return skuIds;
    }

    /**
     * @param at 원가를 고를 시각. 미래여도 된다 — 그 시각의 결과는 이후 원가 추가로 바뀔 수 있다
     */
    public record Point(long skuId, Instant at) {

        public Point {
            Objects.requireNonNull(at, "at");
        }

        Point truncatedToMicros() {
            return new Point(skuId, at.truncatedTo(ChronoUnit.MICROS));
        }
    }
}
