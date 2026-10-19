package com.commercefoundry.common.support.archfixture.compliant.domainlayer.catalog.application.exception;

/** catalog Processor 가 던지는 예외. 부른 컨텍스트의 ExceptionHandler 가 번역한다 — H8 허용. */
public class CatalogException extends RuntimeException {

    public CatalogException(String message) {
        super(message);
    }
}
