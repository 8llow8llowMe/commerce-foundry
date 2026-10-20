package com.commercefoundry.common.support.archfixture.bad.domainlayer.goods.adapter.in.web.advice;

import com.commercefoundry.common.support.archfixture.bad.domainlayer.catalog.application.exception.CatalogException;
import org.springframework.web.bind.annotation.ExceptionHandler;

/** H8 — 다른 컨텍스트 예외를 adapter.in.<진입>.exception 밖에서 받는다. */
public class GoodsWebAdvice {

    @ExceptionHandler(CatalogException.class)
    public String handleCatalogException(CatalogException exception) {
        return exception.getMessage();
    }
}
