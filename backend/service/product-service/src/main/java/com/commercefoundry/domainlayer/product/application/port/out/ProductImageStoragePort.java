package com.commercefoundry.domainlayer.product.application.port.out;

import com.commercefoundry.domainlayer.product.application.port.out.query.ProductImageUploadTicket;
import com.commercefoundry.domainlayer.product.domain.model.ProductImageManagedSource;
import com.commercefoundry.domainlayer.product.domain.model.ProductImageUploadKey;

/**
 * 상품 이미지 오브젝트 스토리지 (#9 F). 스토리지 오류는 업무 오류로 바꿔 올린다 — 꺼짐 {@code PRODUCT_036}(503), 연결 · 권한 · 설정 {@code PRODUCT_035}(503).
 * 첨부(검증 · 복사 · 보상 삭제)는 DB 트랜잭션 밖에서 호출한다.
 */
public interface ProductImageStoragePort {

    /**
     * 임시 업로드 자리를 서명해 준다. 스토리지에 쓰지 않는다(로컬 서명) — 트랜잭션 밖에서 부른다.
     *
     * @param contentLength 올릴 바이트 수 — 서명에 들어가 다른 크기는 스토리지가 거절한다
     */
    /** 검증한 원본을 ETag 조건으로 복사하고 서버의 공개 URL 을 돌려준다. */
    String attach(ProductImageUploadKey key, String targetKey);

    /**
     * 고정한 상품 미디어 원본(#359 C1)을 고정한 ETag 조건으로 복사하고 서버의 공개 URL 을 돌려준다. 원본이 없거나 ETag · 크기 · 형식이 고정 값과 다르면
     * {@code PRODUCT_032}(400)다 — 그 사이 바뀐 원본은 붙이지 않는다. 호출한 쪽(초안 적용)이 운영자 문구로 바꾼다.
     */
    String attachManaged(ProductImageManagedSource source, String targetKey);

    /** 실패 보상. 이 작업이 새로 만든 목적지만 삭제한다. */
    void delete(String targetKey);

    ProductImageUploadTicket issueUpload(ProductImageUploadKey key, long contentLength);
}
