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
