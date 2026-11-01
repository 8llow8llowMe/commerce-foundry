package com.commercefoundry.domainlayer.product.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.port.out.ProductImageStoragePort;
import com.commercefoundry.domainlayer.product.application.port.out.query.ProductImageUploadTicket;
import com.commercefoundry.domainlayer.product.domain.model.ProductImageFormat;
import com.commercefoundry.domainlayer.product.domain.model.ProductImageUploadKey;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

/** 업로드 발급 — 형식 · 크기를 선언대로 보고, 발급한 어드민에게 묶인 키로 서명을 맡긴다. */
class ProductImageUploadProcessorTest {

    private final ProductImageStoragePort port = mock(ProductImageStoragePort.class);
    private final ProductImageUploadProcessor processor = new ProductImageUploadProcessor(port);

    @Test
    @DisplayName("JPEG · 크기 안이면 그 어드민의 키(JPEG 확장자)로 서명한다")
    void issues() {
        ProductImageUploadTicket ticket = new ProductImageUploadTicket("k", "https://s/k?sig", "PUT", Map.of("Content-Type", "image/jpeg"), Instant.EPOCH);
        given(port.issueUpload(any(), eq(524_288L))).willReturn(ticket);

        assertThat(processor.issue(100L, "image/jpeg", 524_288L).uploadKey()).isEqualTo("k");

        ArgumentCaptor<ProductImageUploadKey> key = ArgumentCaptor.forClass(ProductImageUploadKey.class);
        org.mockito.Mockito.verify(port).issueUpload(key.capture(), anyLong());
        assertThat(key.getValue().memberId()).isEqualTo(100L);
        assertThat(key.getValue().format()).isEqualTo(ProductImageFormat.JPEG);
    }

    @ParameterizedTest(name = "{0} / {1}")
    @CsvSource(nullValues = "null", value = {
        "image/svg+xml, 100, PRODUCT_033",
        "null, 100, PRODUCT_033",
        "image/png, 0, PRODUCT_034",
        "image/png, -1, PRODUCT_034",
        "image/png, 10485761, PRODUCT_034",
        "image/png, null, PRODUCT_034"
    })
    @DisplayName("형식이 아니면 033, 크기가 0 이하 · 10MB 초과 · 없음이면 034 이고 서명하지 않는다")
    void rejects(String contentType, Long contentLength, String code) {
        assertThatThrownBy(() -> processor.issue(100L, contentType, contentLength))
            .isInstanceOfSatisfying(ProductException.class, exception -> assertThat(exception.getErrorCode().getCode()).isEqualTo(code));
        verifyNoInteractions(port);
    }

    @Test
    @DisplayName("10MB 정확히는 받는다")
    void maxSizeAllowed() {
        given(port.issueUpload(any(), eq(ProductImageFormat.MAX_BYTES)))
            .willReturn(new ProductImageUploadTicket("k", "https://s/k", "PUT", Map.of(), Instant.EPOCH));
        processor.issue(100L, "image/webp", ProductImageFormat.MAX_BYTES);

        org.mockito.Mockito.verify(port).issueUpload(any(), eq(ProductImageFormat.MAX_BYTES));
        assertThat(ProductErrorCode.IMAGE_SIZE_NOT_ALLOWED.getCode()).isEqualTo("PRODUCT_034");
    }
}
