package com.commercefoundry.common.support.archfixture.violating.domainlayer.goods.domain.model;

import com.commercefoundry.common.support.archfixture.violating.domainlayer.catalog.domain.model.Catalog;
import com.commercefoundry.common.support.archfixture.violating.domainlayer.goods.application.info.GoodsInfo;
import org.springframework.util.Assert;

/** H4 — domain 이 application(Info) · Spring 에 의존한다. H8 — 다른 컨텍스트 domain 을 필드로 갖는다. */
public record Goods(long id, String name, Catalog catalog) {

    public Goods {
        Assert.hasText(name, "name");
    }

    public GoodsInfo toInfo() {
        return new GoodsInfo(id, name);
    }
}
