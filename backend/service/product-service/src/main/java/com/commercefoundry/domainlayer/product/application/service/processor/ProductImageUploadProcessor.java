package com.commercefoundry.domainlayer.product.application.service.processor;

import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.port.out.ProductImageStoragePort;
import com.commercefoundry.domainlayer.product.application.info.ProductImageUploadInfo;
import com.commercefoundry.domainlayer.product.domain.model.ProductImageFormat;
import com.commercefoundry.domainlayer.product.domain.model.ProductImageUploadKey;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 상품 이미지 업로드 발급 (#9 F, D1 presigned PUT). 형식(JPEG · PNG · WebP)과 크기(1B~10MB)를 선언대로 보고 발급한 어드민에게 묶인 키로 서명한다.
 * 선언은 믿지 않는다 — 실제 크기 · 앞부분 바이트는 첨부 때 다시 본다(F2). DB 를 쓰지 않아 트랜잭션이 없다.
 */
@Component
@RequiredArgsConstructor
public class ProductImageUploadProcessor {

    private final ProductImageStoragePort productImageStoragePort;

    /**
     * @param memberId access 토큰의 memberId — 키에 묶인다
     * @throws ProductException 형식 {@code PRODUCT_033} · 크기 {@code 034}(400),
     *     스토리지 {@code 035} · 꺼짐 {@code 036}(503)
     */
    public ProductImageUploadInfo issue(long memberId, String contentType, Long contentLength) {
        ProductImageFormat format = ProductImageFormat.ofContentType(contentType)
            .orElseThrow(() -> new ProductException(ProductErrorCode.IMAGE_FORMAT_NOT_ALLOWED));
        if (contentLength == null || contentLength <= 0 || contentLength > ProductImageFormat.MAX_BYTES) {
            throw new ProductException(ProductErrorCode.IMAGE_SIZE_NOT_ALLOWED);
        }
        return ProductImageUploadInfo.from(productImageStoragePort.issueUpload(ProductImageUploadKey.issue(memberId, format), contentLength));
    }
}
