package com.commercefoundry.domainlayer.product.application.port.out;

import com.commercefoundry.domainlayer.product.application.port.out.query.ProductImageUploadTicket;
import com.commercefoundry.domainlayer.product.domain.model.ProductImageUploadKey;

/**
 * 상품 이미지 오브젝트 스토리지 (#9 F). 스토리지 오류는 업무 오류로 바꿔 올린다 — 꺼짐 {@code PRODUCT_036}(503), 연결 · 권한 · 설정 {@code PRODUCT_035}(503).
 * 첨부(검증 · 복사 · 보상 삭제)는 F2 에서 더한다.
 */
public interface ProductImageStoragePort {

    /**
     * 임시 업로드 자리를 서명해 준다. 스토리지에 쓰지 않는다(로컬 서명) — 트랜잭션 밖에서 부른다.
     *
     * @param contentLength 올릴 바이트 수 — 서명에 들어가 다른 크기는 스토리지가 거절한다
     */
    ProductImageUploadTicket issueUpload(ProductImageUploadKey key, long contentLength);
}
