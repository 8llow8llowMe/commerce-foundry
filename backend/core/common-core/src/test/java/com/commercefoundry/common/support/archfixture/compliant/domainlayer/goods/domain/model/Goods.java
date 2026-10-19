package com.commercefoundry.common.support.archfixture.compliant.domainlayer.goods.domain.model;

/** 다른 컨텍스트 domain 은 아이디로만 가리킨다. */
public record Goods(long id, long catalogId, String name) {
}
