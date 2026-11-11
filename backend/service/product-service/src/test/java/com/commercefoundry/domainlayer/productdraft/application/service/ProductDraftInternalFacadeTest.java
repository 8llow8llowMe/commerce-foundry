package com.commercefoundry.domainlayer.productdraft.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.commercefoundry.ai.client.AiImage;
import com.commercefoundry.domainlayer.productdraft.adapter.in.internal.presenter.ProductDraftInternalPresenter;
import com.commercefoundry.domainlayer.productdraft.application.exception.ProductDraftErrorCode;
import com.commercefoundry.domainlayer.productdraft.application.exception.ProductDraftException;
import com.commercefoundry.domainlayer.productdraft.application.port.out.ProductDraftImageStoragePort;
import com.commercefoundry.domainlayer.productdraft.application.port.out.ProductDraftRepositoryPort;
import com.commercefoundry.domainlayer.productdraft.application.port.out.query.ProductDraftImageObjectQueryResult;
import com.commercefoundry.domainlayer.productdraft.application.service.processor.ProductDraftImageProcessor;
import com.commercefoundry.domainlayer.productdraft.application.service.processor.ProductDraftProcessor;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraft;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftFacts;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftImage;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftImageSource;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftImageType;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftStatus;
import com.commercefoundry.global.media.MediaUrlPolicy;
import com.commercefoundry.global.properties.ProductDraftProperties;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 실제 Facade → Processor → 포트 경로로 internal 생성 입력의 플래그 · 판정 · 바이트 재검증을 본다(포트만 목). */
class ProductDraftInternalFacadeTest {

    private static final Instant NOW = Instant.parse("2026-11-11T03:00:00Z");
    private static final String KEY = "products/media/7/0f8fad5b-d9cb-469f-a165-70867728950e/" + "a".repeat(64) + ".png";
    private static final String ETAG = "\"etag-1\"";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};
    private static final ProductDraftFacts FACTS = new ProductDraftFacts("린넨 저고리", 12L, List.of("린넨 100%"), List.of("찬물 손세탁"), "총장 62cm\n가슴 54cm", "대한민국",
        List.of(), "차분하게", List.of("여름"), List.of("최고"));

    private final ProductDraftRepositoryPort repository = mock(ProductDraftRepositoryPort.class);
    private final ProductDraftImageStoragePort storage = mock(ProductDraftImageStoragePort.class);

    private ProductDraftInternalFacade facade(boolean generationEnabled) {
        ProductDraftProperties properties = new ProductDraftProperties(Duration.ofDays(7), generationEnabled);
        ProductDraftProcessor processor = new ProductDraftProcessor(repository, new SnowflakeIdGenerator(1, 1), MediaUrlPolicy.of("https://cdn.example.com"),
            properties, Clock.fixed(NOW, ZoneOffset.UTC));
        return new ProductDraftInternalFacade(processor, new ProductDraftImageProcessor(storage), new ProductDraftInternalPresenter(), properties);
    }

    private void stored(List<ProductDraftImage> images) {
        given(repository.findById(7L)).willReturn(Optional.of(
            new ProductDraft(7L, ProductDraftStatus.REQUESTED, 100L, FACTS, images, null, null, NOW.plusSeconds(60), NOW.minusSeconds(60))));
    }

    private static ProductDraftImage pinnedImage() {
        return new ProductDraftImage("https://cdn.example.com/" + KEY, ProductDraftImageType.THUMBNAIL, 1200, 1600,
            new ProductDraftImageSource(KEY, ETAG, PNG.length, "image/png"));
    }

    private static void assertCode(ThrowingCallable call, ProductDraftErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ProductDraftException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }

    @Test
    @DisplayName("플래그가 닫혀 있으면(기본) 두 API 모두 040 이고 초안 · 저장소를 읽지 않는다")
    void closedFlag() {
        stored(List.of(pinnedImage()));
        ProductDraftInternalFacade closed = facade(false);

        assertCode(() -> closed.generationInput(7L), ProductDraftErrorCode.GENERATION_DISABLED);
        assertCode(() -> closed.generationImage(7L, 0), ProductDraftErrorCode.GENERATION_DISABLED);
        verify(repository, never()).findById(anyLong());
        verify(storage, never()).read(anyString(), anyInt(), anyString());
    }

    @Test
    @DisplayName("생성 입력은 사실 · 이미지 순번 · 유형 · 형식 · 크기만 싣고 키 · ETag · 주소 · 만든 어드민 · 카테고리를 싣지 않는다")
    void generationInput() {
        stored(List.of(pinnedImage()));

        var response = facade(true).generationInput(7L);

        assertThat(response.draftId()).isEqualTo("7");
        assertThat(response.status()).isEqualTo("REQUESTED");
        assertThat(response.expiresAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(response.facts().nameHint()).isEqualTo("린넨 저고리");
        assertThat(response.facts().measurementsNote()).isEqualTo("총장 62cm\n가슴 54cm");
        assertThat(response.imageCount()).isEqualTo(1);
        var image = response.images().getFirst();
        assertThat(image.index()).isZero();
        assertThat(image.imageType()).isEqualTo("THUMBNAIL");
        assertThat(image.contentType()).isEqualTo("image/png");
        assertThat(image.byteLength()).isEqualTo(PNG.length);
        assertThat(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(response))
            .doesNotContain(KEY, "etag-1", "cdn.example.com", "requestedBy", "categoryId", "source");
    }

    @Test
    @DisplayName("이미지 바이트는 고정한 ETag 조건으로 읽고, 바뀌었으면 044 · 순번 밖은 045 · 옛 초안은 043 이다")
    void generationImage() {
        stored(List.of(pinnedImage()));
        ProductDraftInternalFacade open = facade(true);
        given(storage.read(KEY, AiImage.MAX_BYTES, ETAG)).willReturn(new ProductDraftImageObjectQueryResult.Read(PNG, "image/png"));
        assertThat(open.generationImage(7L, 0)).isEqualTo(PNG);

        given(storage.read(KEY, AiImage.MAX_BYTES, ETAG)).willReturn(new ProductDraftImageObjectQueryResult.Changed());
        assertCode(() -> open.generationImage(7L, 0), ProductDraftErrorCode.GENERATION_IMAGE_CHANGED);

        assertCode(() -> open.generationImage(7L, -1), ProductDraftErrorCode.GENERATION_IMAGE_NOT_FOUND);
        assertCode(() -> open.generationImage(7L, 1), ProductDraftErrorCode.GENERATION_IMAGE_NOT_FOUND);

        stored(List.of(new ProductDraftImage("https://cdn.example.com/" + KEY, ProductDraftImageType.THUMBNAIL, null, null)));
        assertCode(() -> open.generationInput(7L), ProductDraftErrorCode.GENERATION_INPUT_UNAVAILABLE);
        assertCode(() -> open.generationImage(7L, 0), ProductDraftErrorCode.GENERATION_INPUT_UNAVAILABLE);
    }
}
