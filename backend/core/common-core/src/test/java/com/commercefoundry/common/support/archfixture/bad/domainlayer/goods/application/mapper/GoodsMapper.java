package com.commercefoundry.common.support.archfixture.bad.domainlayer.goods.application.mapper;

import com.commercefoundry.common.support.archfixture.bad.domainlayer.goods.application.info.GoodsInfo;

public class GoodsMapper {

    public String nameOf(GoodsInfo info) {
        return info.name();
    }
}
