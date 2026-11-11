package com.commercefoundry.domainlayer.productdraft.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.commercefoundry.ai.client.AiImage;
import com.commercefoundry.domainlayer.productdraft.application.exception.ProductDraftErrorCode;
import com.commercefoundry.domainlayer.productdraft.application.exception.ProductDraftException;
import com.commercefoundry.domainlayer.productdraft.application.port.out.ProductDraftImageStoragePort;
import com.commercefoundry.domainlayer.productdraft.application.port.out.query.ProductDraftImageObjectQueryResult;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftImage;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftImageSource;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftImageType;
import java.util.List;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 초안 이미지 키 · ETag 고정(D3 · D11)과 생성 작업용 바이트 재검증. */
class ProductDraftImageProcessorTest {

    private static final String BASE = "https://cdn.example.com/";
    private static final String KEY_A = "products/media/7/0f8fad5b-d9cb-469f-a165-70867728950e/" + "a".repeat(64) + ".png";
    private static final String KEY_B = "products/media/7/1f8fad5b-d9cb-469f-a165-70867728950e/" + "b".repeat(64) + ".jpg";
    private static final String ETAG = "\"etag-1\"";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};

    private final ProductDraftImageStoragePort storage = mock(ProductDraftImageStoragePort.class);
    private final ProductDraftImageProcessor processor = new ProductDraftImageProcessor(storage);

    private static ProductDraftImage image(String key, ProductDraftImageType type) {
        return new ProductDraftImage(BASE + key, type, 1200, 1600);
    }

    private void managed(String key) {
        given(storage.managedKeyOf(BASE + key)).willReturn(Optional.of(key));
    }

    private static void assertCode(ThrowingCallable call, ProductDraftErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ProductDraftException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }

    @Nested
    @DisplayName("키 · ETag 고정")
    class Pin {

        @Test
        @DisplayName("관리 키로 바꾸고 HEAD 의 ETag · 크기 · 형식을 순서대로 고정한다 — 주소 · 유형 · 가로세로는 그대로다")
        void pins() {
            managed(KEY_A);
            managed(KEY_B);
            given(storage.head(KEY_A)).willReturn(new ProductDraftImageObjectQueryResult.Stored(12, "image/png", ETAG));
            given(storage.head(KEY_B)).willReturn(new ProductDraftImageObjectQueryResult.Stored(AiImage.MAX_BYTES, "image/jpeg", "\"etag-2\""));

            List<ProductDraftImage> pinned = processor.pin(List.of(image(KEY_A, ProductDraftImageType.THUMBNAIL), image(KEY_B, ProductDraftImageType.GALLERY)));

            assertThat(pinned).extracting(ProductDraftImage::source).containsExactly(
                new ProductDraftImageSource(KEY_A, ETAG, 12, "image/png"), new ProductDraftImageSource(KEY_B, "\"etag-2\"", AiImage.MAX_BYTES, "image/jpeg"));
            assertThat(pinned.get(0).url()).isEqualTo(BASE + KEY_A);
            assertThat(pinned.get(0).imageType()).isEqualTo(ProductDraftImageType.THUMBNAIL);
            assertThat(pinned.get(0).width()).isEqualTo(1200);
        }

        @Test
        @DisplayName("이미지가 없으면 저장소를 부르지 않는다")
        void noImages() {
            assertThat(processor.pin(List.of())).isEmpty();
            verify(storage, never()).head(anyString());
        }

        @Test
        @DisplayName("키로 바꾸지 못한 주소(퍼센트 인코딩 · 외부 · 공개 주소 불일치)는 031 이고 HEAD 를 부르지 않는다")
        void unresolvedKey() {
            managed(KEY_A);
            given(storage.managedKeyOf(BASE + "products/media/a%20b.png")).willReturn(Optional.empty());

            assertCode(() -> processor.pin(List.of(image(KEY_A, ProductDraftImageType.THUMBNAIL),
                new ProductDraftImage(BASE + "products/media/a%20b.png", ProductDraftImageType.GALLERY, null, null))), ProductDraftErrorCode.IMAGE_NOT_MANAGED);
            verify(storage, never()).head(anyString());
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "uploads/display-media/0f8fad5b-d9cb-469f-a165-70867728950e.png",
            "products/700/4611686018427387905.png",
            "products/media/7/0f8fad5b-d9cb-469f-a165-70867728950e/short.png",
            "products/media/0/0f8fad5b-d9cb-469f-a165-70867728950e/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png",
            "products/media/7/0f8fad5b-d9cb-469f-a165-70867728950e/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.gif",
            "legacy/cafe24/a.jpg"
        })
        @DisplayName("허용 패턴(products/media/{actor}/{UUID}/{SHA-256}.{jpg|png|webp}) 밖 키는 031 이다 — 업로드 원본 · 상품별 키 · 이관 이미지")
        void keyOutsidePattern(String key) {
            managed(key);
            assertCode(() -> processor.pin(List.of(image(key, ProductDraftImageType.THUMBNAIL))), ProductDraftErrorCode.IMAGE_NOT_MANAGED);
            verify(storage, never()).head(anyString());
        }

        @Test
        @DisplayName("저장소에 없으면 033 이다")
        void missing() {
            managed(KEY_A);
            given(storage.head(KEY_A)).willReturn(new ProductDraftImageObjectQueryResult.Missing());
            assertCode(() -> processor.pin(List.of(image(KEY_A, ProductDraftImageType.THUMBNAIL))), ProductDraftErrorCode.IMAGE_NOT_FOUND);
        }

        @Test
        @DisplayName("5MiB(AiImage.MAX_BYTES) 초과 · 빈 객체 · 허용 형식 밖 · 키 확장자와 다른 형식 · 빈 ETag 는 036 이다")
        void sizeAndFormat() {
            managed(KEY_A);
            List<ProductDraftImage> images = List.of(image(KEY_A, ProductDraftImageType.THUMBNAIL));
            for (ProductDraftImageObjectQueryResult head : List.of(
                new ProductDraftImageObjectQueryResult.Stored(AiImage.MAX_BYTES + 1L, "image/png", ETAG),
                new ProductDraftImageObjectQueryResult.Stored(0, "image/png", ETAG),
                new ProductDraftImageObjectQueryResult.Stored(12, null, ETAG),
                new ProductDraftImageObjectQueryResult.Stored(12, "image/jpeg", ETAG),
                new ProductDraftImageObjectQueryResult.Stored(12, "image/png", " ")
            )) {
                given(storage.head(KEY_A)).willReturn(head);
                assertCode(() -> processor.pin(images), ProductDraftErrorCode.IMAGE_FORMAT_NOT_ALLOWED);
            }
        }

        @Test
        @DisplayName("저장소 장애는 503 PRODUCT_DRAFT_501 그대로 올린다")
        void storageUnavailable() {
            managed(KEY_A);
            given(storage.head(KEY_A)).willThrow(new ProductDraftException(ProductDraftErrorCode.IMAGE_STORAGE_UNAVAILABLE));
            assertCode(() -> processor.pin(List.of(image(KEY_A, ProductDraftImageType.THUMBNAIL))), ProductDraftErrorCode.IMAGE_STORAGE_UNAVAILABLE);
        }
    }

    @Nested
    @DisplayName("생성 작업용 바이트")
    class GenerationBytes {

        private final ProductDraftImageSource source = new ProductDraftImageSource(KEY_A, ETAG, PNG.length, "image/png");

        @Test
        @DisplayName("고정한 ETag 조건 · 5MiB 상한으로 읽고 크기 · 형식이 같으면 바이트를 돌려준다")
        void reads() {
            given(storage.read(KEY_A, AiImage.MAX_BYTES, ETAG)).willReturn(new ProductDraftImageObjectQueryResult.Read(PNG, "image/png"));
            assertThat(processor.generationBytes(source)).isEqualTo(PNG);
        }

        @Test
        @DisplayName("ETag 변경 · 없어짐 · 형식 밖 · 크기 다름 · magic bytes 형식 다름은 모두 044(CHANGED, 재시도 불가)다")
        void changed() {
            for (ProductDraftImageObjectQueryResult result : List.of(
                new ProductDraftImageObjectQueryResult.Changed(),
                new ProductDraftImageObjectQueryResult.Missing(),
                new ProductDraftImageObjectQueryResult.Invalid(),
                new ProductDraftImageObjectQueryResult.Read(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}, "image/png"),
                new ProductDraftImageObjectQueryResult.Read(PNG, "image/webp")
            )) {
                given(storage.read(KEY_A, AiImage.MAX_BYTES, ETAG)).willReturn(result);
                assertCode(() -> processor.generationBytes(source), ProductDraftErrorCode.GENERATION_IMAGE_CHANGED);
            }
        }

        @Test
        @DisplayName("저장된 키가 products/media 모양이 아니면 저장소를 읽지 않고 044 다")
        void foreignKeyNeverRead() {
            String fileName = KEY_A.substring(KEY_A.lastIndexOf('/') + 1);
            for (String key : List.of("uploads/display-media/" + fileName, "products/1/" + fileName)) {
                ProductDraftImageSource foreign = new ProductDraftImageSource(key, ETAG, PNG.length, "image/png");
                assertCode(() -> processor.generationBytes(foreign), ProductDraftErrorCode.GENERATION_IMAGE_CHANGED);
            }
            verify(storage, never()).read(anyString(), anyInt(), anyString());
        }
    }
}
