package com.commercefoundry.domainlayer.product.application.service;

import com.commercefoundry.domainlayer.product.application.model.PriceBackfillModel.Mode;
import com.commercefoundry.domainlayer.product.application.model.PriceBackfillModel.Report;
import com.commercefoundry.domainlayer.product.application.model.PriceBackfillModel.Tally;
import com.commercefoundry.domainlayer.product.application.model.PriceMigrationPreflightModel;
import com.commercefoundry.domainlayer.product.application.model.PriceMigrationPreflightModel.Verdict;
import com.commercefoundry.domainlayer.product.application.port.in.PriceBackfillUseCase;
import com.commercefoundry.domainlayer.product.application.service.processor.PriceBackfillProcessor;
import com.commercefoundry.domainlayer.product.application.service.processor.PriceMigrationPreflightProcessor;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 사전 점검 → (READY 일 때만) 상품 순회 backfill → (APPLY 면) 사전 점검 재실행. 트랜잭션을 걸지 않는다 —
 * 사전 점검 읽기는 페이지마다, backfill 은 상품마다 자체 TX 를 쓴다. 바깥 TX 는 커넥션만 붙잡는다.
 */
@Service
@RequiredArgsConstructor
public class PriceBackfillFacade implements PriceBackfillUseCase {

    private final PriceMigrationPreflightProcessor priceMigrationPreflightProcessor;
    private final PriceBackfillProcessor priceBackfillProcessor;

    @Override
    public Report run(Mode mode) {
        Objects.requireNonNull(mode, "mode");
        PriceMigrationPreflightModel.Report before = priceMigrationPreflightProcessor.run();
        if (before.verdict() == Verdict.BLOCKED) {
            return new Report(mode, Verdict.BLOCKED, null, null);
        }
        Tally tally = priceBackfillProcessor.run(mode);
        PriceMigrationPreflightModel.Report after = mode == Mode.APPLY ? priceMigrationPreflightProcessor.run() : null;
        return new Report(mode, Verdict.READY, tally, after);
    }
}
