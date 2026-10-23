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

    /** 이력 행 → 구간 행 순서로 바로 내보낸다 — 실패가 커밋 시점이 아니라 이 호출에서 드러나 호출자 트랜잭션(정책 교체)과 함께 롤백된다. */
    @Override
    public void insert(ShippingPolicyHistory history) {
        shippingPolicyHistoryRepository.saveAndFlush(shippingPolicyHistoryMapper.toEntity(history));
        List<ShippingPolicyHistoryAreaEntity> areas = shippingPolicyHistoryMapper.toAreaEntities(history);
        if (!areas.isEmpty()) {
            shippingPolicyHistoryAreaRepository.saveAllAndFlush(areas);
        }
    }

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
