package com.commercefoundry.domainlayer.product.adapter.out.persistence;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductOptionGroupEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductOptionValueEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.ProductOptionGroupRepository;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.ProductOptionValueRepository;
import com.commercefoundry.domainlayer.product.application.mapper.ProductOptionMapper;
import com.commercefoundry.domainlayer.product.application.port.out.ProductOptionRepositoryPort;
import com.commercefoundry.domainlayer.product.domain.model.OptionGroup;
import com.commercefoundry.domainlayer.product.domain.model.OptionValue;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProductOptionRepositoryAdapter implements ProductOptionRepositoryPort {

    private final ProductOptionGroupRepository productOptionGroupRepository;
    private final ProductOptionValueRepository productOptionValueRepository;
    private final ProductOptionMapper productOptionMapper;

    @Override
    public List<OptionGroup> findGroupsByProductId(long productId) {
        return productOptionMapper.toGroups(productOptionGroupRepository.findAllByProductIdOrderByDisplayOrderAscIdAsc(productId));
    }

    @Override
    public List<OptionValue> findValuesByProductId(long productId) {
        return productOptionMapper.toValues(productOptionValueRepository.findAllByProductIdOrderByDisplayOrderAscIdAsc(productId));
    }

    @Override
    public List<OptionGroup> findGroupsByIds(Collection<Long> optionGroupIds) {
        if (optionGroupIds.isEmpty()) {
            return List.of();
        }
        return productOptionMapper.toGroups(productOptionGroupRepository.findAllById(optionGroupIds));
    }

    @Override
    public List<OptionValue> findValuesByIds(Collection<Long> optionValueIds) {
        if (optionValueIds.isEmpty()) {
            return List.of();
        }
        return productOptionMapper.toValues(productOptionValueRepository.findAllById(optionValueIds));
    }

    /** 기존 행의 이름과 순서만 부분 UPDATE한다. */
    @Override
    public void updateLabels(List<OptionGroup> existingGroups, List<OptionValue> existingValues, List<OptionGroup> groups, List<OptionValue> values) {
        var now = java.time.Instant.now();
        var blocked = new java.util.HashSet<String>();
        existingGroups.forEach(group -> blocked.add(group.name()));
        existingValues.forEach(value -> blocked.add(value.name()));
        groups.forEach(group -> blocked.add(group.name()));
        values.forEach(value -> blocked.add(value.name()));
        var oldGroups = existingGroups.stream().collect(java.util.stream.Collectors.toMap(OptionGroup::id, java.util.function.Function.identity()));
        var oldValues = existingValues.stream().collect(java.util.stream.Collectors.toMap(OptionValue::id, java.util.function.Function.identity()));
        // Exclude current, final and previously reserved names, including retired rows.
        for (var group : groups) {
            if (!group.name().equals(oldGroups.get(group.id()).name())) {
                requireUpdated(productOptionGroupRepository.updateLabel(group.id(), temporaryName(blocked), oldGroups.get(group.id()).displayOrder(), now));
            }
        }
        for (var value : values) {
            if (!value.name().equals(oldValues.get(value.id()).name())) {
                requireUpdated(productOptionValueRepository.updateLabel(value.id(), temporaryName(blocked), oldValues.get(value.id()).displayOrder(), now));
            }
        }
        for (var group : groups) {
            if (!group.equals(oldGroups.get(group.id()))) requireUpdated(productOptionGroupRepository.updateLabel(group.id(), group.name(), group.displayOrder(), now));
        }
        for (var value : values) {
            if (!value.equals(oldValues.get(value.id()))) requireUpdated(productOptionValueRepository.updateLabel(value.id(), value.name(), value.displayOrder(), now));
        }
    }

    private static String temporaryName(java.util.Set<String> blocked) {
        for (int candidate = 0; ; candidate++) {
            String name = "__edit_" + candidate;
            if (blocked.add(name)) return name;
        }
    }

    private static void requireUpdated(int count) {
        if (count != 1) throw new IllegalStateException("Option disappeared during version-fenced edit");
    }

    /** 엔티티가 {@code Persistable} 이라 새 행은 persist(INSERT) 로 간다. 그룹을 먼저 넣고 값을 넣는다. */
    @Override
    public void insertAll(List<OptionGroup> groups, List<OptionValue> values) {
        productOptionGroupRepository.saveAll(groups.stream()
            .map(group -> ProductOptionGroupEntity.builder()
                .id(group.id()).productId(group.productId()).name(group.name()).displayOrder(group.displayOrder()).retiredAt(group.retiredAt()).build())
            .toList());
        productOptionValueRepository.saveAll(values.stream()
            .map(value -> ProductOptionValueEntity.builder()
                .id(value.id()).optionGroupId(value.optionGroupId()).productId(value.productId()).name(value.name())
                .displayOrder(value.displayOrder()).retiredAt(value.retiredAt()).build())
            .toList());
    }
}
