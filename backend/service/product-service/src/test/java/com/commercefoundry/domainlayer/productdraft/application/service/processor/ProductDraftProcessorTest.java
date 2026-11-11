package com.commercefoundry.domainlayer.productdraft.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.commercefoundry.domainlayer.productdraft.application.command.ProductDraftCreateCommand;
import com.commercefoundry.domainlayer.productdraft.application.exception.ProductDraftErrorCode;
import com.commercefoundry.domainlayer.productdraft.application.exception.ProductDraftException;
import com.commercefoundry.domainlayer.productdraft.application.port.out.ProductDraftRepositoryPort;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 초안 생성(이미지 판정 · 만료 시각) · 거절(재전송 · 적용 · 만료) 판정. */
class ProductDraftProcessorTest {

    private static final Instant NOW = Instant.parse("2026-11-01T03:00:00Z");
    private static final String BASE = "https://cdn.example.com/media";
    private static final ProductDraftFacts FACTS = new ProductDraftFacts("린넨 저고리", 12L, List.of("린넨 100%"), null, null, null, null, null, null, null);

    private final ProductDraftRepositoryPort port = mock(ProductDraftRepositoryPort.class);
    private final ProductDraftProcessor processor = processor(BASE);

    private ProductDraftProcessor processor(String publicBaseUrl) {
        return new ProductDraftProcessor(port, new SnowflakeIdGenerator(1, 1), MediaUrlPolicy.of(publicBaseUrl),
            new ProductDraftProperties(Duration.ofDays(7), false), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ProductDraftImage image(String path, ProductDraftImageType type) {
        return new ProductDraftImage(BASE + path, type, null, null);
    }

    private static ProductDraftImage pinned(String path, ProductDraftImageType type) {
        return image(path, type).pinned(new ProductDraftImageSource("products/media" + path, "\"etag\"", 10L, "image/jpeg"));
    }

    private static ProductDraftCreateCommand command(List<ProductDraftImage> images) {
        return ProductDraftCreateCommand.builder().requestedByMemberId(100L).facts(FACTS).images(images).build();
    }

    @Nested
    @DisplayName("생성")
    class Create {

        @Test
        @DisplayName("생성 대기 초안을 저장한다 — 만료는 지금 + 7일, 만든 어드민 · 입력 · 이미지 순서가 그대로다")
        void creates() {
            given(port.insert(any())).willAnswer(invocation -> invocation.getArgument(0));
            List<ProductDraftImage> images = List.of(pinned("/a.jpg", ProductDraftImageType.THUMBNAIL), pinned("/b.jpg", ProductDraftImageType.GALLERY));

            processor.create(command(images));

            ArgumentCaptor<ProductDraft> saved = ArgumentCaptor.forClass(ProductDraft.class);
            verify(port).insert(saved.capture());
            assertThat(saved.getValue().status()).isEqualTo(ProductDraftStatus.REQUESTED);
            assertThat(saved.getValue().expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
            assertThat(saved.getValue().requestedByMemberId()).isEqualTo(100L);
            assertThat(saved.getValue().facts()).isEqualTo(FACTS);
            assertThat(saved.getValue().images()).isEqualTo(images);
            assertThat(saved.getValue().id()).isPositive();
        }

        @Test
        @DisplayName("이미지가 없으면 미디어 저장소 설정을 보지 않는다 — 사실만으로도 만든다")
        void noImagesWithoutStorage() {
            given(port.insert(any())).willAnswer(invocation -> invocation.getArgument(0));

            processor(null).create(command(List.of()));

            verify(port).insert(any());
        }

        @Test
        @DisplayName("이미지 판정 — 대표 두 장 034 → 같은 주소 035 → 저장소 미설정 032 → 공개 주소 밖 030 순이고, 실패하면 저장하지 않는다")
        void imageRules() {
            assertCode(processor, List.of(image("/a.jpg", ProductDraftImageType.THUMBNAIL), image("/a.jpg", ProductDraftImageType.THUMBNAIL)),
                ProductDraftErrorCode.THUMBNAIL_DUPLICATED);
            assertCode(processor, List.of(image("/a.jpg", ProductDraftImageType.THUMBNAIL), image("/a.jpg", ProductDraftImageType.GALLERY)), ProductDraftErrorCode.IMAGE_DUPLICATED);
            assertCode(processor(null), List.of(image("/a.jpg", ProductDraftImageType.THUMBNAIL)), ProductDraftErrorCode.MEDIA_STORAGE_NOT_CONFIGURED);
            assertCode(processor, List.of(new ProductDraftImage("https://evil.example.com/a.jpg", ProductDraftImageType.THUMBNAIL, null, null)),
                ProductDraftErrorCode.IMAGE_URL_NOT_ALLOWED);
            verify(port, never()).insert(any());
        }

        @Test
        @DisplayName("원본을 고정하지 않은 이미지는 저장하지 않는다 — 키 고정을 건너뛴 호출 순서 버그다")
        void refusesUnpinnedImages() {
            assertThatThrownBy(() -> processor.create(command(List.of(image("/a.jpg", ProductDraftImageType.THUMBNAIL)))))
                .isInstanceOf(IllegalStateException.class);
            verify(port, never()).insert(any());
        }

        private void assertCode(ProductDraftProcessor target, List<ProductDraftImage> images, ProductDraftErrorCode code) {
            assertThatThrownBy(() -> target.validateImages(images))
                .isInstanceOfSatisfying(ProductDraftException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
        }
    }

    @Nested
    @DisplayName("생성 입력 (internal)")
    class Generation {

        private void stored(ProductDraftStatus status, Instant expiresAt, List<ProductDraftImage> images) {
            given(port.findById(7L)).willReturn(Optional.of(new ProductDraft(7L, status, 100L, FACTS, images, null, null, expiresAt, NOW.minusSeconds(60))));
        }

        @Test
        @DisplayName("생성 대기 · 생성 중이고 원본을 고정한 초안이면 그대로 돌려준다 — 이미지가 없는 초안도 된다")
        void readable() {
            stored(ProductDraftStatus.REQUESTED, NOW.plusSeconds(60), List.of(pinned("/a.jpg", ProductDraftImageType.THUMBNAIL)));
            assertThat(processor.generationDraft(7L).id()).isEqualTo(7L);

            stored(ProductDraftStatus.GENERATING, NOW.plusSeconds(60), List.of());
            assertThat(processor.generationDraft(7L).images()).isEmpty();
        }

        @Test
        @DisplayName("없음 001 · 만료 022 · 거절 041 · 검토 대기 · 실패 · 적용 042 를 구분한다")
        void refusals() {
            assertCode(() -> processor.generationDraft(0L), ProductDraftErrorCode.NOT_FOUND_DRAFT);
            given(port.findById(8L)).willReturn(Optional.empty());
            assertCode(() -> processor.generationDraft(8L), ProductDraftErrorCode.NOT_FOUND_DRAFT);

            stored(ProductDraftStatus.REQUESTED, NOW, List.of());
            assertCode(() -> processor.generationDraft(7L), ProductDraftErrorCode.DRAFT_EXPIRED);

            given(port.findById(7L)).willReturn(Optional.of(
                new ProductDraft(7L, ProductDraftStatus.REJECTED, 100L, FACTS, List.of(), 200L, NOW.minusSeconds(5), NOW.plusSeconds(60), NOW.minusSeconds(60))));
            assertCode(() -> processor.generationDraft(7L), ProductDraftErrorCode.GENERATION_DRAFT_REJECTED);

            for (ProductDraftStatus status : List.of(ProductDraftStatus.READY, ProductDraftStatus.FAILED, ProductDraftStatus.APPLIED)) {
                stored(status, NOW.plusSeconds(60), List.of());
                assertCode(() -> processor.generationDraft(7L), ProductDraftErrorCode.GENERATION_STATUS_INVALID);
            }
        }

        @Test
        @DisplayName("원본을 고정하지 않은 옛 초안(키 없음)은 입력 불가 043 이다 — 바이트 읽기도 같다")
        void legacyDraftWithoutSource() {
            stored(ProductDraftStatus.REQUESTED, NOW.plusSeconds(60), List.of(pinned("/a.jpg", ProductDraftImageType.THUMBNAIL), image("/b.jpg", ProductDraftImageType.GALLERY)));

            assertCode(() -> processor.generationDraft(7L), ProductDraftErrorCode.GENERATION_INPUT_UNAVAILABLE);
            assertCode(() -> processor.generationImage(7L, 0), ProductDraftErrorCode.GENERATION_INPUT_UNAVAILABLE);
        }

        @Test
        @DisplayName("이미지 순번은 0부터이고 음수 · 범위 밖은 045 다")
        void imageIndex() {
            ProductDraftImage second = pinned("/b.jpg", ProductDraftImageType.GALLERY);
            stored(ProductDraftStatus.REQUESTED, NOW.plusSeconds(60), List.of(pinned("/a.jpg", ProductDraftImageType.THUMBNAIL), second));

            assertThat(processor.generationImage(7L, 1)).isEqualTo(second);
            assertCode(() -> processor.generationImage(7L, -1), ProductDraftErrorCode.GENERATION_IMAGE_NOT_FOUND);
            assertCode(() -> processor.generationImage(7L, 2), ProductDraftErrorCode.GENERATION_IMAGE_NOT_FOUND);
        }

        private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ProductDraftErrorCode code) {
            assertThatThrownBy(call).isInstanceOfSatisfying(ProductDraftException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
        }
    }

    @Nested
    @DisplayName("거절")
    class Reject {

        private ProductDraft stored(ProductDraftStatus status, Instant expiresAt) {
            ProductDraft draft = new ProductDraft(7L, status, 100L, FACTS, List.of(), null, null, expiresAt, NOW.minusSeconds(60));
            given(port.findById(7L)).willReturn(Optional.of(draft));
            return draft;
        }

        @Test
        @DisplayName("검토 대기 초안을 거절해 저장한다")
        void rejects() {
            stored(ProductDraftStatus.READY, NOW.plusSeconds(60));

            ProductDraft rejected = processor.reject(7L, 200L, NOW);

            assertThat(rejected.status()).isEqualTo(ProductDraftStatus.REJECTED);
            verify(port).updateDecision(rejected);
        }

        @Test
        @DisplayName("이미 거절한 초안은 그대로 돌려준다 — 처음 거절한 어드민 · 시각을 바꾸지 않고 저장하지 않는다")
        void alreadyRejected() {
            ProductDraft first = new ProductDraft(7L, ProductDraftStatus.REJECTED, 100L, FACTS, List.of(), 300L, NOW.minusSeconds(10), NOW.minusSeconds(1), NOW.minusSeconds(60));
            given(port.findById(7L)).willReturn(Optional.of(first));

            assertThat(processor.reject(7L, 200L, NOW)).isEqualTo(first);
            verify(port, never()).updateDecision(any());
        }

        @Test
        @DisplayName("적용한 초안은 020, 기간이 지난 초안은 022, 없는 초안은 001 이고 저장하지 않는다")
        void refusals() {
            stored(ProductDraftStatus.APPLIED, NOW.plusSeconds(60));
            assertCode(7L, ProductDraftErrorCode.DRAFT_NOT_REJECTABLE);

            stored(ProductDraftStatus.READY, NOW);
            assertCode(7L, ProductDraftErrorCode.DRAFT_EXPIRED);

            given(port.findById(8L)).willReturn(Optional.empty());
            assertCode(8L, ProductDraftErrorCode.NOT_FOUND_DRAFT);
            verify(port, never()).updateDecision(any());
        }

        private void assertCode(long draftId, ProductDraftErrorCode code) {
            assertThatThrownBy(() -> processor.reject(draftId, 200L, NOW))
                .isInstanceOfSatisfying(ProductDraftException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
        }
    }
}
