package com.commercefoundry.common.support.archfixture.compliant.domainlayer.goods.adapter.in.support;

/** 진입들이 같이 쓰는 변환 함수. DTO 는 두지 않는다. */
public final class GoodsLabelSupport {

    private GoodsLabelSupport() {
    }

    public static String label(String name) {
        return "[" + name + "]";
    }
}
