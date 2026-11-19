package com.commercefoundry.domainlayer.productdraft.application.port.out;

import com.commercefoundry.domainlayer.productdraft.application.port.out.query.ProductDraftGenerationResultQueryResult;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraft;

/** 붙어 있는 작업의 결과를 한 번 조회한다. 호출자는 DB 트랜잭션 밖에서 실행한다. */
public interface ProductDraftGenerationResultQueryPort {

    ProductDraftGenerationResultQueryResult result(ProductDraft draft);
}
