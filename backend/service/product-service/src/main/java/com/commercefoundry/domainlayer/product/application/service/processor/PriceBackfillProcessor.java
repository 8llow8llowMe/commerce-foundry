package com.commercefoundry.domainlayer.product.application.service.processor;

import com.commercefoundry.domainlayer.product.application.exception.PriceMigrationPreflightException;
import com.commercefoundry.domainlayer.product.application.exception.PriceMigrationPreflightException.Reason;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.model.PriceBackfillModel.Mode;
import com.commercefoundry.domainlayer.product.application.model.PriceBackfillModel.Outcome;
import com.commercefoundry.domainlayer.product.application.model.PriceBackfillModel.ProductResult;
import com.commercefoundry.domainlayer.product.application.model.PriceBackfillModel.Tally;
import com.commercefoundry.domainlayer.product.application.model.PriceMigrationReadModel.Criteria;
import com.commercefoundry.domainlayer.product.application.model.PriceMigrationReadModel.Lane;
import com.commercefoundry.domainlayer.product.application.port.out.PriceMigrationReadPort;
import com.commercefoundry.domainlayer.product.application.port.out.query.PriceMigrationReadQueryResult.Bounds;
import com.commercefoundry.domainlayer.product.application.port.out.query.PriceMigrationReadQueryResult.ProductRow;
import com.commercefoundry.domainlayer.product.application.port.out.query.PriceMigrationReadQueryResult.Row;
import com.commercefoundry.domainlayer.product.domain.model.LegacyPriceWritePlan;
import java.util.EnumMap;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 상품 lane 을 keyset 으로 끝까지 순회하며 상품마다 {@link PriceBackfillProductProcessor} 를 부른다. 트랜잭션을 걸지 않는다.
 *
 * <p>상품 한 건의 실패는 결과로 세고 다음 상품으로 간다 — 동시 변경은 CONFLICT, 불변식 위반은 INVALID 다. 자동 재시도하지 않는다.
 * 다시 실행하면 빠진 행만 다시 계획되므로 중단 지점부터 이어진다. 그 밖의 예외(DB 장애 등)는 그대로 올려 실행을 멈춘다.
 * 상한 이후 새로 생긴 상품은 보지 않는다 — 새 상품은 등록 writer 가 같은 TX 에서 가격행을 만든다.
 */
@Component
@RequiredArgsConstructor
public class PriceBackfillProcessor {

    static final int DEFAULT_PAGE_SIZE = 500;

    private final PriceMigrationReadPort priceMigrationReadPort;
    private final PriceBackfillProductProcessor priceBackfillProductProcessor;

    public Tally run(Mode mode) {
        return run(mode, DEFAULT_PAGE_SIZE);
    }

    /** 테스트에서 페이지 경계를 작게 두기 위한 진입이다. pageSize 범위 검사는 {@link Criteria} 가 한다. */
    Tally run(Mode mode, int pageSize) {
        Bounds bounds = priceMigrationReadPort.captureBounds();
        if (bounds == null) {
            throw new PriceMigrationPreflightException(Reason.PORT_CONTRACT_VIOLATION);
        }
        var outcomes = new EnumMap<Outcome, Long>(Outcome.class);
        long productPrices = 0;
        long skuPrices = 0;
        Long upper = bounds.upper(Lane.PRODUCT);
        Long after = null;
        while (upper != null) {
            List<Row> rows = priceMigrationReadPort.readPage(new Criteria(Lane.PRODUCT, upper, after, pageSize));
            if (rows == null || rows.size() > pageSize) {
                throw new PriceMigrationPreflightException(Reason.PORT_CONTRACT_VIOLATION);
            }
            if (rows.isEmpty()) {
                break;
            }
            for (Row row : rows) {
                if (!(row instanceof ProductRow) || row.id() == null || row.id() > upper || after != null && row.id() <= after) {
                    throw new PriceMigrationPreflightException(Reason.PORT_CONTRACT_VIOLATION);
                }
                after = row.id();
                ProductResult result = fillOne(row.id(), mode);
                outcomes.merge(result.outcome(), 1L, Long::sum);
                productPrices += result.productPrices();
                skuPrices += result.skuPrices();
            }
        }
        return new Tally(outcomes, productPrices, skuPrices);
    }

    private ProductResult fillOne(long productId, Mode mode) {
        if (productId <= 0) {
            return ProductResult.of(Outcome.INVALID);
        }
        try {
            return priceBackfillProductProcessor.fill(productId, mode);
        } catch (ProductException exception) {
            if (exception.getErrorCode() == ProductErrorCode.PRODUCT_MODIFICATION_CONFLICT) {
                return ProductResult.of(Outcome.CONFLICT);
            }
            throw exception;
        } catch (IllegalStateException | IllegalArgumentException | ArithmeticException
                 | LegacyPriceWritePlan.RequestRuleViolationException exception) {
            return ProductResult.of(Outcome.INVALID);
        }
    }
}
