package com.commercefoundry.common.support.archfixture.bad.domainlayer.goods.adapter.in.web.exception;

import com.commercefoundry.common.support.archfixture.bad.domainlayer.catalog.application.exception.CatalogException;

/** H8 — exception 패키지라도 이름이 *ExceptionHandler 가 아니면 다른 컨텍스트 예외를 쓰지 않는다. */
public class CatalogErrorTranslator {

    public String translate(CatalogException exception) {
        return exception.getMessage();
    }
}
