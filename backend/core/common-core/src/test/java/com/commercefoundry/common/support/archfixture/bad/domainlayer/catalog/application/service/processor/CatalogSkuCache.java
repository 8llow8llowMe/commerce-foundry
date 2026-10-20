package com.commercefoundry.common.support.archfixture.bad.domainlayer.catalog.application.service.processor;

/** H8 — processor 패키지에 있어도 이름이 *Processor 가 아니면 다른 컨텍스트가 쓰지 않는다. */
public class CatalogSkuCache {

    public String lookup(long skuId) {
        return String.valueOf(skuId);
    }
}
