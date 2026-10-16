package com.commercefoundry.domainlayer.product.application.port.out;

import com.commercefoundry.domainlayer.product.domain.model.CustomOrderField;
import java.util.List;

public interface ProductCustomOrderFieldRepositoryPort {

    /** 노출 순서 → 아이디 오름차순. */
    List<CustomOrderField> findAllByProductId(long productId);
}
