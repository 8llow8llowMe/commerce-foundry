package com.commercefoundry.common.support.archfixture.ok.domainlayer.goods.application.mapper;

import com.commercefoundry.common.support.archfixture.ok.domainlayer.goods.adapter.out.persistence.entity.GoodsEntity;
import com.commercefoundry.common.support.archfixture.ok.domainlayer.goods.domain.model.Goods;

/** 문서화된 예외 ③ — mapper 는 Entity ↔ Domain 변환이라 Entity 를 참조한다. */
public class GoodsMapper {

    public Goods toDomain(GoodsEntity entity) {
        return new Goods(entity.id, entity.catalogId, entity.name);
    }
}
