package com.commercefoundry.common.support.archfixture.ok.domainlayer.goods.adapter.in.web.presenter;

import com.commercefoundry.common.support.archfixture.ok.domainlayer.goods.adapter.in.support.GoodsLabelSupport;
import com.commercefoundry.common.support.archfixture.ok.domainlayer.goods.adapter.in.web.dto.response.GoodsResponse;
import com.commercefoundry.common.support.archfixture.ok.domainlayer.goods.application.info.GoodsInfo;

public class GoodsWebPresenter {

    public GoodsResponse toResponse(GoodsInfo info) {
        return new GoodsResponse(String.valueOf(info.id()), GoodsLabelSupport.label(info.name()));
    }
}
