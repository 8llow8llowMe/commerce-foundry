package com.commercefoundry.domainlayer.product.adapter.out.persistence;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductCustomOrderFieldEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.ProductCustomOrderFieldRepository;
import com.commercefoundry.domainlayer.product.application.mapper.ProductCustomOrderFieldMapper;
import com.commercefoundry.domainlayer.product.application.port.out.ProductCustomOrderFieldRepositoryPort;
import com.commercefoundry.domainlayer.product.domain.model.CustomOrderField;
import java.util.Collection;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProductCustomOrderFieldRepositoryAdapter implements ProductCustomOrderFieldRepositoryPort {

    private final ProductCustomOrderFieldRepository productCustomOrderFieldRepository;
    private final ProductCustomOrderFieldMapper productCustomOrderFieldMapper;

    @Override
    public List<CustomOrderField> findAllByProductId(long productId) {
        return productCustomOrderFieldMapper.toDomains(productCustomOrderFieldRepository.findAllByProductIdOrderByDisplayOrderAscIdAsc(productId));
    }

    @Override
    public List<CustomOrderField> findAllByProductIds(Collection<Long> productIds) {
        if (productIds.isEmpty()) {
            return List.of();
        }
        return productCustomOrderFieldMapper.toDomains(
            productCustomOrderFieldRepository.findAllByProductIdInOrderByProductIdAscDisplayOrderAscIdAsc(productIds));
    }

    @Override
    public void replace(long productId, List<CustomOrderField> existing, List<CustomOrderField> next) {
        Set<Long> kept = next.stream().map(CustomOrderField::id).collect(Collectors.toSet());
        Set<Long> existingIds = existing.stream().map(CustomOrderField::id).collect(Collectors.toSet());
        List<Long> removed = existingIds.stream().filter(id -> !kept.contains(id)).toList();
        if (!removed.isEmpty()) {
            productCustomOrderFieldRepository.deleteAllByProductIdAndIdIn(productId, removed);
        }
        Instant now = Instant.now();
        for (CustomOrderField field : next) {
            if (field.productId() != productId) {
                throw new IllegalArgumentException("다른 상품의 입력 항목입니다: productId=" + productId + ", fieldId=" + field.id());
            }
            if (existingIds.contains(field.id())) {
                if (productCustomOrderFieldRepository.updateDefinition(field.id(), productId, field.label(), field.inputType(), field.unit(),
                    field.required(), field.minValue(), field.maxValue(), field.stepValue(), field.helpText(), field.displayOrder(), now) != 1) {
                    throw new IllegalStateException("고칠 입력 항목이 없습니다: fieldId=" + field.id());
                }
            } else {
                productCustomOrderFieldRepository.save(toNewEntity(field));
            }
        }
        try {
            productCustomOrderFieldRepository.flush();
        } catch (org.springframework.dao.DataIntegrityViolationException exception) {
            throw new com.commercefoundry.domainlayer.product.application.exception.ProductException(
                com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode.PRODUCT_MODIFICATION_CONFLICT, exception);
        }
    }

    private static ProductCustomOrderFieldEntity toNewEntity(CustomOrderField field) {
        return ProductCustomOrderFieldEntity.builder()
            .id(field.id()).productId(field.productId()).fieldKey(field.fieldKey()).label(field.label()).inputType(field.inputType())
            .unit(field.unit()).required(field.required()).minValue(field.minValue()).maxValue(field.maxValue()).stepValue(field.stepValue())
            .helpText(field.helpText()).displayOrder(field.displayOrder())
            .build();
    }
}
