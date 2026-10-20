package com.commercefoundry.common.support.archfixture.ok.domainlayer.goods.adapter.in.admin.presenter;

import com.commercefoundry.common.support.archfixture.ok.domainlayer.goods.adapter.in.admin.dto.response.GoodsAdminResponse;
import com.commercefoundry.common.support.archfixture.ok.domainlayer.goods.adapter.in.support.GoodsLabelSupport;
import com.commercefoundry.common.support.archfixture.ok.domainlayer.goods.application.info.GoodsInfo;

public class GoodsAdminPresenter {

    public GoodsAdminResponse toResponse(GoodsInfo info) {
        return new GoodsAdminResponse(String.valueOf(info.id()), GoodsLabelSupport.label(info.name()), info.catalogName());
    }
}
