package com.commercefoundry.domainlayer.product.adapter.in.bootstrap;

import com.commercefoundry.domainlayer.product.application.model.PriceBackfillModel.Mode;
import com.commercefoundry.domainlayer.product.application.model.PriceBackfillModel.Outcome;
import com.commercefoundry.domainlayer.product.application.model.PriceBackfillModel.Report;
import com.commercefoundry.domainlayer.product.application.model.PriceMigrationPreflightModel.Finding;
import com.commercefoundry.domainlayer.product.application.port.in.PriceBackfillUseCase;
import com.commercefoundry.global.properties.PriceBackfillProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 기준통화 가격행 backfill (#20 PR-2a) — 기동이 부르는 진입점이다(auth {@code AdminSeedRunner} 와 같은 {@code adapter/in/bootstrap}).
 *
 * <p>설정이 OFF(기본)면 아무것도 하지 않는다. DRY_RUN · APPLY 는 한 번 돌고 결과를 한 줄로 남긴다 — 건수와 판정만 남기고
 * 식별자 · 금액 · 통화 원문은 남기지 않는다. 실패해도 기동은 멈추지 않는다(가격 이관이 서비스 가용성을 막지 않게). 실패 로그에는
 * 예외 종류만 남긴다(원문 메시지 · cause 를 싣지 않는다). 운영 APPLY 는 사용자 승인 사항이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PriceBackfillRunner implements ApplicationRunner {

    private final PriceBackfillProperties priceBackfillProperties;
    private final PriceBackfillUseCase priceBackfillUseCase;

    @Override
    public void run(ApplicationArguments args) {
        Mode mode = switch (priceBackfillProperties.mode()) {
            case OFF -> null;
            case DRY_RUN -> Mode.DRY_RUN;
            case APPLY -> Mode.APPLY;
        };
        if (mode == null) {
            log.info("price backfill mode=OFF");
            return;
        }
        Report report;
        try {
            report = priceBackfillUseCase.run(mode);
        } catch (RuntimeException exception) {
            log.error("price backfill aborted mode={} error={}", mode, exception.getClass().getSimpleName());
            return;
        }
        if (report.blocked()) {
            log.warn("price backfill mode={} preflight=BLOCKED — no rows written; run the preflight and fix blocking findings first", mode);
            return;
        }
        var tally = report.tally();
        var after = report.after();
        log.info("price backfill mode={} preflight=READY complete={} filled={} wouldFill={} conflict={} invalid={} notFound={} "
                + "productPrices={} skuPrices={} after={} afterProductBaseMissing={} afterSkuBaseMissing={}",
            mode, tally.count(Outcome.COMPLETE), tally.count(Outcome.FILLED), tally.count(Outcome.WOULD_FILL),
            tally.count(Outcome.CONFLICT), tally.count(Outcome.INVALID), tally.count(Outcome.NOT_FOUND),
            tally.productPrices(), tally.skuPrices(),
            after == null ? "-" : after.verdict(),
            after == null ? "-" : after.count(Finding.PRODUCT_PRICE_BASE_MISSING),
            after == null ? "-" : after.count(Finding.SKU_PRICE_BASE_MISSING));
    }
}
