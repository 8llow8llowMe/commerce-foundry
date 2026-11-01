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
            new ProductDraftProperties(Duration.ofDays(7)), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ProductDraftImage image(String path, ProductDraftImageType type) {
        return new ProductDraftImage(BASE + path, type, null, null);
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
            List<ProductDraftImage> images = List.of(image("/a.jpg", ProductDraftImageType.THUMBNAIL), image("/b.jpg", ProductDraftImageType.GALLERY));

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

        private void assertCode(ProductDraftProcessor target, List<ProductDraftImage> images, ProductDraftErrorCode code) {
            assertThatThrownBy(() -> target.create(command(images)))
                .isInstanceOfSatisfying(ProductDraftException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
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
