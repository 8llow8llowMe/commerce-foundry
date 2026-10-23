package com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence;

import com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.entity.ShippingPolicyHistoryAreaEntity;
import com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.entity.ShippingPolicyHistoryEntity;
import com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.repository.ShippingPolicyHistoryAreaRepository;
import com.commercefoundry.domainlayer.shippingfee.adapter.out.persistence.repository.ShippingPolicyHistoryRepository;
import com.commercefoundry.domainlayer.shippingfee.application.mapper.ShippingPolicyHistoryMapper;
import com.commercefoundry.domainlayer.shippingfee.application.port.out.ShippingPolicyHistoryRepositoryPort;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingPolicyHistory;
import com.commercefoundry.shared.commerce.ShippingScope;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ShippingPolicyHistoryRepositoryAdapter implements ShippingPolicyHistoryRepositoryPort {

    private final ShippingPolicyHistoryRepository shippingPolicyHistoryRepository;
    private final ShippingPolicyHistoryAreaRepository shippingPolicyHistoryAreaRepository;
    private final ShippingPolicyHistoryMapper shippingPolicyHistoryMapper;

    /**
     * 이력 행({@code saveAndFlush}) → 구간 행({@code saveAllAndFlush}) 순서로 바로 내보낸다 — 제약 위반 · 아이디 충돌이 커밋 시점이 아니라 이 호출에서
     * {@link org.springframework.dao.DataAccessException} 으로 드러나 호출자 트랜잭션(정책 교체)과 함께 롤백된다.
     */
    @Override
    public void insert(ShippingPolicyHistory history) {
        shippingPolicyHistoryRepository.saveAndFlush(shippingPolicyHistoryMapper.toEntity(history));
        List<ShippingPolicyHistoryAreaEntity> areas = shippingPolicyHistoryMapper.toAreaEntities(history);
        if (!areas.isEmpty()) {
            shippingPolicyHistoryAreaRepository.saveAllAndFlush(areas);
        }
    }

    /**
     * 쿼리는 목록 1 + 전체 건수 1(첫 페이지가 다 차지 않으면 Spring Data 가 생략) + 그 페이지 이력의 구간 in 절 1 이다 — 이력이 없으면 구간을 읽지 않는다.
     * 정렬 전제는 아이디 내림차순이다 — 교체는 버전 검사로 직렬화되므로 id 순서가 교체 순서다.
     */
    @Override
    public Page<ShippingPolicyHistory> findDomesticPage(int page, int size) {
        Page<ShippingPolicyHistoryEntity> histories = shippingPolicyHistoryRepository.findAllByScopeOrderByIdDesc(ShippingScope.DOMESTIC, PageRequest.of(page, size));
        List<Long> historyIds = histories.getContent().stream().map(ShippingPolicyHistoryEntity::getId).toList();
        Map<Long, List<ShippingPolicyHistoryAreaEntity>> areasByHistoryId = historyIds.isEmpty() ? Map.of()
            : shippingPolicyHistoryAreaRepository.findAllByShippingPolicyHistoryIdIn(historyIds).stream()
                .collect(Collectors.groupingBy(ShippingPolicyHistoryAreaEntity::getShippingPolicyHistoryId));
        return histories.map(history -> shippingPolicyHistoryMapper.toDomain(history, areasByHistoryId.getOrDefault(history.getId(), List.of())));
    }
}
