package com.commercefoundry.domainlayer.product.application.service;

import com.commercefoundry.domainlayer.product.application.model.PriceMigrationPreflightModel.Report;
import com.commercefoundry.domainlayer.product.application.port.in.PriceMigrationPreflightUseCase;
import com.commercefoundry.domainlayer.product.application.service.processor.PriceMigrationPreflightProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * B1c-B 사전검증 진입. 트랜잭션을 걸지 않는다 — 읽기 adapter가 상한·페이지마다 자체 REQUIRES_NEW read-only TX를 쓰므로
 * 여기서 TX를 열면 전체 순회 동안 커넥션만 붙잡고 snapshot 일관성은 얻지 못한다.
 */
@Service
@RequiredArgsConstructor
public class PriceMigrationPreflightFacade implements PriceMigrationPreflightUseCase {

    private final PriceMigrationPreflightProcessor priceMigrationPreflightProcessor;

    @Override
    public Report run() {
        return priceMigrationPreflightProcessor.run();
    }
}
