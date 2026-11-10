package com.commercefoundry.domainlayer.product.application.service.processor;

import com.commercefoundry.domainlayer.product.application.model.PriceMigrationPreflightModel.Report;
import com.commercefoundry.domainlayer.product.application.model.PriceMigrationReadModel.Criteria;
import com.commercefoundry.domainlayer.product.application.model.PriceMigrationReadModel.Lane;
import com.commercefoundry.domainlayer.product.application.port.out.PriceMigrationReadPort;
import com.commercefoundry.domainlayer.product.application.port.out.query.PriceMigrationReadQueryResult.Bounds;
import com.commercefoundry.domainlayer.product.application.port.out.query.PriceMigrationReadQueryResult.ProductPriceRow;
import com.commercefoundry.domainlayer.product.application.port.out.query.PriceMigrationReadQueryResult.ProductRow;
import com.commercefoundry.domainlayer.product.application.port.out.query.PriceMigrationReadQueryResult.Row;
import com.commercefoundry.domainlayer.product.application.port.out.query.PriceMigrationReadQueryResult.SkuPriceRow;
import com.commercefoundry.domainlayer.product.application.port.out.query.PriceMigrationReadQueryResult.SkuRow;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** 어댑터 keyset 계약(id<=upper, id>after, ID 오름차순, 최대 pageSize)을 메모리에서 흉내 내는 포트 스텁이다. */
class PriceMigrationReadStub implements PriceMigrationReadPort {
    final Map<Lane, List<Row>> rows = new EnumMap<>(Lane.class);
    final List<Criteria> calls = new ArrayList<>();

    PriceMigrationReadStub() {
        for (Lane lane : Lane.values()) rows.put(lane, new ArrayList<>());
    }

    @Override
    public Bounds captureBounds() {
        return new Bounds(max(Lane.PRODUCT), max(Lane.SKU), max(Lane.PRODUCT_PRICE), max(Lane.SKU_PRICE));
    }

    @Override
    public List<Row> readPage(Criteria criteria) {
        calls.add(criteria);
        if (criteria.upper() == null) return List.of();
        return rows.get(criteria.lane()).stream().sorted(Comparator.comparing(Row::id))
            .filter(row -> row.id() <= criteria.upper() && (criteria.after() == null || row.id() > criteria.after()))
            .limit(criteria.pageSize()).toList();
    }

    Report run(int pageSize) {
        return new PriceMigrationPreflightProcessor(this).run(pageSize, 1_000_000);
    }

    private Long max(Lane lane) {
        return rows.get(lane).stream().map(Row::id).max(Long::compare).orElse(null);
    }

    PriceMigrationReadStub product(long id, Long sale, String currency, Long list, String listCurrency) {
        rows.get(Lane.PRODUCT).add(new ProductRow(id, sale, currency, list, listCurrency, "STOP_SELLING", false, null, 0L));
        return this;
    }

    PriceMigrationReadStub sku(long id, Long productId, Long amount, String currency, String status) {
        rows.get(Lane.SKU).add(new SkuRow(id, productId, amount, currency, status));
        return this;
    }

    PriceMigrationReadStub productPrice(long id, Long productId, Long sale, String currency, Long list, String listCurrency) {
        rows.get(Lane.PRODUCT_PRICE).add(new ProductPriceRow(id, productId, sale, currency, list, listCurrency));
        return this;
    }

    PriceMigrationReadStub skuPrice(long id, Long skuId, Long amount, String currency) {
        rows.get(Lane.SKU_PRICE).add(new SkuPriceRow(id, skuId, amount, currency));
        return this;
    }

    /** 현재 legacy 행을 그대로 옮긴 기준통화 가격행을 추가한다(가격행 ID = 100000 + 원천 ID). */
    PriceMigrationReadStub mirror() {
        for (Row row : List.copyOf(rows.get(Lane.PRODUCT))) {
            ProductRow p = (ProductRow) row;
            productPrice(100_000 + p.id(), p.id(), p.saleAmount(), p.saleCurrency(), p.listAmount(), p.listCurrency());
        }
        for (Row row : List.copyOf(rows.get(Lane.SKU))) {
            SkuRow s = (SkuRow) row;
            skuPrice(100_000 + s.id(), s.id(), s.additionalAmount(), s.additionalCurrency());
        }
        return this;
    }
}
