package com.commercefoundry.common.support.archfixture.violating.domainlayer.goods.adapter.in.web.exception;

import com.commercefoundry.common.support.archfixture.violating.domainlayer.catalog.application.exception.CatalogException;
import com.commercefoundry.common.support.archfixture.violating.domainlayer.catalog.application.port.out.CatalogReaderPort;
import org.springframework.web.bind.annotation.ExceptionHandler;

/** H8 — ExceptionHandler 라도 다른 컨텍스트 예외(허용) 밖의 타입(out-port)은 쓰지 않는다. */
public class GoodsExceptionHandler {

    private final CatalogReaderPort catalogReaderPort;

    public GoodsExceptionHandler(CatalogReaderPort catalogReaderPort) {
        this.catalogReaderPort = catalogReaderPort;
    }

    @ExceptionHandler(CatalogException.class)
    public String handleCatalogException(CatalogException exception) {
        return catalogReaderPort.findCatalog(0).name() + exception.getMessage();
    }
}
