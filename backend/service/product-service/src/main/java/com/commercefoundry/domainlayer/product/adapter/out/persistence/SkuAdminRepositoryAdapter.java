package com.commercefoundry.domainlayer.product.adapter.out.persistence;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.ProductSkuVersionRepository;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.SkuAdminRepository;
import com.commercefoundry.domainlayer.product.application.port.out.SkuAdminRepositoryPort;
import com.commercefoundry.domainlayer.product.domain.model.Sku;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
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
        // 일괄 조회 뒤 실제 변경 행을 하나씩 flush해 UPDATE 순서를 보장한다.
        for (var sku : skus.stream().sorted(java.util.Comparator.comparingLong(Sku::id)).toList()) {
            var entity = existing.get(sku.id());
            if (entity.replaceEditableProperties(sku)) {
                skuRepository.flush();
            }
        }
    }
}
