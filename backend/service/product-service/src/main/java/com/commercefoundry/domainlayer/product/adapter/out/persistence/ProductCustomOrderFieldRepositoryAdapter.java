package com.commercefoundry.domainlayer.product.adapter.out.persistence;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.ProductCustomOrderFieldRepository;
import com.commercefoundry.domainlayer.product.application.mapper.ProductCustomOrderFieldMapper;
import com.commercefoundry.domainlayer.product.application.port.out.ProductCustomOrderFieldRepositoryPort;
import com.commercefoundry.domainlayer.product.domain.model.CustomOrderField;
import java.util.List;
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
}
