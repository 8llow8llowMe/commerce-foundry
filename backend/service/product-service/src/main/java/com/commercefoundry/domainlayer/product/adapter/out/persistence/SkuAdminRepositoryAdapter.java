package com.commercefoundry.domainlayer.product.adapter.out.persistence;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.SkuEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.ProductSkuVersionRepository;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.SkuAdminRepository;
import com.commercefoundry.domainlayer.product.application.exception.SkuAdminErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.SkuAdminException;
import com.commercefoundry.domainlayer.product.application.port.out.SkuAdminRepositoryPort;
import com.commercefoundry.domainlayer.product.domain.model.Sku;
import com.commercefoundry.persistence.util.ConstraintViolations;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SkuAdminRepositoryAdapter implements SkuAdminRepositoryPort {
    private final ProductSkuVersionRepository productVersions;
    private final SkuAdminRepository skuRepository;

    @Override
    public boolean claimProductVersion(long productId, long version) {
        return productVersions.claimVersion(productId, version, Instant.now()) == 1;
    }

    @Override
    public void replaceEditableProperties(List<Sku> skus) {
        if (skus.isEmpty()) {
            return;
        }
        var existing = skuRepository.findAllByProductIdOrderByIdAsc(skus.getFirst().productId()).stream()
            .collect(Collectors.toMap(entity -> entity.getId(), Function.identity()));
        var ordered = skus.stream().sorted(Comparator.comparingLong(Sku::id)).toList();
        releaseReassignedBarcodes(ordered, existing);
        try {
            // 실제 변경 행을 하나씩 flush해 UPDATE 순서를 보장한다.
            for (var sku : ordered) {
                if (existing.get(sku.id()).replaceEditableProperties(sku)) {
                    skuRepository.flush();
                }
            }
        } catch (DataIntegrityViolationException exception) {
            if (ConstraintViolations.violates(exception, SkuEntity.BARCODE_UNIQUE_CONSTRAINT)) {
                throw new SkuAdminException(SkuAdminErrorCode.BARCODE_DUPLICATED, exception);
            }
            throw exception;
        }
    }

    private void releaseReassignedBarcodes(List<Sku> ordered, Map<Long, SkuEntity> existing) {
        var targetByBarcode = ordered.stream().filter(sku -> sku.barcode() != null)
            .collect(Collectors.toMap(sku -> sku.barcode().value(), Sku::id));
        // 같은 상품 안의 교환·양도를 허용하되 다른 트랜잭션에는 중간 상태를 노출하지 않는다.
        for (var sku : ordered) {
            var entity = existing.get(sku.id());
            Long targetId = entity.getBarcode() == null ? null : targetByBarcode.get(entity.getBarcode());
            if (targetId != null && targetId != sku.id()) {
                entity.releaseBarcode();
                skuRepository.flush();
            }
        }
    }
}
