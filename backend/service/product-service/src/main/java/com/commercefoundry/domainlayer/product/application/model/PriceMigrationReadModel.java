package com.commercefoundry.domainlayer.product.application.model;

import com.commercefoundry.domainlayer.product.application.exception.PriceMigrationReadException;

/** 이행 전 원천 관측을 위한 내부 값이다. 정상 판정·집계·공개 응답으로 사용하지 않는다. */
public final class PriceMigrationReadModel {
    private PriceMigrationReadModel() { }

    public enum Lane { PRODUCT, SKU, PRODUCT_PRICE, SKU_PRICE }

    /** after=null은 첫 페이지다. 0·음수·MIN_VALUE도 원천 PK이므로 시작값으로 사용하지 않는다. */
    public record Criteria(Lane lane, Long upper, Long after, int pageSize) {
        public Criteria {
            if (lane == null || pageSize < 1 || pageSize > 500) {
                throw new PriceMigrationReadException(PriceMigrationReadException.Reason.INVALID_CRITERIA);
            }
        }
        @Override public String toString() { return "Criteria[redacted]"; }
    }
}
