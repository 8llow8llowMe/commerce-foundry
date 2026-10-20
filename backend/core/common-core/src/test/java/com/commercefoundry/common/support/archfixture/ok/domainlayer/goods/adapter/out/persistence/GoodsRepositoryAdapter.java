package com.commercefoundry.common.support.archfixture.ok.domainlayer.goods.adapter.out.persistence;

import com.commercefoundry.common.support.archfixture.ok.domainlayer.goods.adapter.out.persistence.entity.GoodsEntity;
import com.commercefoundry.common.support.archfixture.ok.domainlayer.goods.application.mapper.GoodsMapper;
import com.commercefoundry.common.support.archfixture.ok.domainlayer.goods.application.port.out.GoodsReaderPort;
import com.commercefoundry.common.support.archfixture.ok.domainlayer.goods.application.port.out.query.GoodsQueryResult;
import com.commercefoundry.common.support.archfixture.ok.domainlayer.goods.domain.model.Goods;

/** out-adapter 가 port · mapper 를 쓰는 것은 바깥 → 안쪽이라 허용이다. */
public class GoodsRepositoryAdapter implements GoodsReaderPort {

    private final GoodsMapper goodsMapper = new GoodsMapper();

    @Override
    public Goods findGoods(long goodsId) {
        return goodsMapper.toDomain(new GoodsEntity());
    }

    @Override
    public GoodsQueryResult findSummary(long goodsId) {
        return new GoodsQueryResult(goodsId, "goods");
    }
}
