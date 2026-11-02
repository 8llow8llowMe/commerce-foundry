package com.commercefoundry.domainlayer.product.adapter.out.persistence;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.StockAlertPolicyRepository;
import com.commercefoundry.domainlayer.product.application.mapper.StockAlertPolicyMapper;
import com.commercefoundry.domainlayer.product.application.port.out.StockAlertPolicyRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.query.StockAlertQueryResult;
import com.commercefoundry.domainlayer.product.domain.model.StockAlertPolicy;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StockAlertPolicyRepositoryAdapter implements StockAlertPolicyRepositoryPort {

    private final StockAlertPolicyRepository repository;
    private final StockAlertPolicyMapper mapper;
    private final Clock clock;

    @Override
    public Optional<StockAlertPolicy> findBySkuId(long skuId) {
        return repository.findById(skuId).map(mapper::toDomain);
    }

    @Override
    public Page<StockAlertQueryResult> findLowStocks(int page, int size) {
        return repository.findLowStocks(PageRequest.of(page, size));
    }

    @Override
    public StockAlertPolicy save(StockAlertPolicy policy) {
        if (repository.updatePolicy(policy.skuId(), policy.thresholdQuantity(), clock.instant().truncatedTo(ChronoUnit.MICROS)) == 0) {
            repository.saveAndFlush(mapper.toEntity(policy));
        }
        return findBySkuId(policy.skuId()).orElseThrow();
    }

    @Override
    public void delete(long skuId) {
        repository.deleteById(skuId);
    }
}
