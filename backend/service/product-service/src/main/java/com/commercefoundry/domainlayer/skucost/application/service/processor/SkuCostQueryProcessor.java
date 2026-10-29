package com.commercefoundry.domainlayer.skucost.application.service.processor;

import com.commercefoundry.domainlayer.skucost.application.command.SkuCostAtQueryCommand;
import com.commercefoundry.domainlayer.skucost.application.info.SkuCostsAtInfo;
import com.commercefoundry.domainlayer.skucost.application.port.out.SkuCostRepositoryPort;
import com.commercefoundry.domainlayer.skucost.domain.model.SkuCost;
import com.commercefoundry.domainlayer.skucost.domain.model.SkuCostTimeline;
import com.commercefoundry.domainlayer.skucost.domain.model.SkuCostType;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

/**
 * SKU 원가 조회 — 지금 적용되는 유형별 원가, 이력 페이지, 서비스 간 시점 원가. SKU 가 있는지는 Facade 가 상품 컨텍스트로 먼저 본다.
 */
@Component
@RequiredArgsConstructor
public class SkuCostQueryProcessor {

    private final SkuCostRepositoryPort skuCostRepositoryPort;
    private final Clock clock;

    /**
     * 지금 적용되는 유형별 원가 — 유형 선언 순서이고 미입력 유형은 빠진다. 질의가 유형별 최신 행을 골라 오고,
     * {@link SkuCostTimeline#resolveAt} 이 같은 규칙으로 한 번 더 걸러 유형당 하나 · 순서를 보장한다.
     * 기준 시각은 저장과 같이 마이크로초로 자른다 — 드라이버는 나노초를 반올림해 바인딩하므로, 자르지 않으면 같은 마이크로초에
     * 저장된 행을 질의는 고르고 메모리 필터는 미래로 버려 그 유형이 미입력처럼 빠진다.
     */
    public List<SkuCost> getCurrent(long skuId) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        return SkuCostTimeline.resolveAt(skuCostRepositoryPort.findCurrent(skuId, now), now);
    }

    /**
     * (SKU, 시각) 쌍마다 그 시각에 적용되는 유형별 원가 — 요청 순서 그대로다. 원가 행은 <b>질의 한 번</b>으로 읽는다: 모든 SKU 의 시작이
     * 가장 늦은 조회 시각 이하인 행을 가져와 SKU 별로 묶고, 쌍마다 {@link SkuCostTimeline#resolveAt} 으로 고른다. 원가 이력은 자주 바뀌지 않아
     * SKU 100개의 이력을 한 번에 들고 와도 작다. 원가가 없는 SKU 는 빈 목록이다. 쌍의 시각은 호출한 쪽이 마이크로초로 잘라 넘긴다
     * ({@link SkuCostAtQueryCommand}).
     *
     * @param points 있는 SKU 의 쌍만 — SKU 존재 확인은 Facade 가 상품 컨텍스트로 먼저 한다. 비어 있으면 질의 없이 빈 목록이다
     */
    public List<SkuCostsAtInfo> getCostsAt(List<SkuCostAtQueryCommand.Point> points) {
        if (points.isEmpty()) {
            return List.of();
        }
        Set<Long> skuIds = points.stream().map(SkuCostAtQueryCommand.Point::skuId).collect(Collectors.toCollection(LinkedHashSet::new));
        Instant maxAt = points.stream().map(SkuCostAtQueryCommand.Point::at).max(Comparator.naturalOrder()).orElseThrow();
        Map<Long, List<SkuCost>> rowsBySkuId = skuCostRepositoryPort.findAllUpTo(skuIds, maxAt).stream()
            .collect(Collectors.groupingBy(SkuCost::skuId));
        return points.stream()
            .map(point -> new SkuCostsAtInfo(
                point.skuId(), point.at(), SkuCostTimeline.resolveAt(rowsBySkuId.getOrDefault(point.skuId(), List.of()), point.at())
            ))
            .toList();
    }

    /** @param costType null 이면 모든 유형 */
    public Page<SkuCost> getHistories(long skuId, SkuCostType costType, int page, int size) {
        return skuCostRepositoryPort.findHistoryPage(skuId, costType, page, size);
    }
}
