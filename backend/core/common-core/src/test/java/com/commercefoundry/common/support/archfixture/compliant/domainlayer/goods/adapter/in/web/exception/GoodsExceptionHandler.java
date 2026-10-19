package com.commercefoundry.common.support.archfixture.compliant.domainlayer.goods.adapter.in.web.exception;

import com.commercefoundry.common.support.archfixture.compliant.domainlayer.catalog.application.exception.CatalogException;
import org.springframework.web.bind.annotation.ExceptionHandler;

/** 다른 컨텍스트 예외는 부른 컨텍스트의 *ExceptionHandler 가 자기 코드로 번역한다 — H8 허용(adapter.in.<진입>.exception → application/exception). */
public class GoodsExceptionHandler {

    @ExceptionHandler(CatalogException.class)
    public String handleCatalogException(CatalogException exception) {
        return "GOODS_503:" + exception.getMessage();
    }
}
