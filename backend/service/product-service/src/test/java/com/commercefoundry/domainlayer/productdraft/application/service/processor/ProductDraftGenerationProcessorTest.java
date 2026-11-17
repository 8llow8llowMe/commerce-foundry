package com.commercefoundry.domainlayer.productdraft.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.commercefoundry.domainlayer.productdraft.application.exception.ProductDraftErrorCode;
import com.commercefoundry.domainlayer.productdraft.application.exception.ProductDraftException;
import com.commercefoundry.domainlayer.productdraft.application.port.out.ProductDraftRepositoryPort;
import com.commercefoundry.domainlayer.productdraft.application.port.out.ProductDraftGenerationPort;
import com.commercefoundry.domainlayer.productdraft.application.port.out.query.ProductDraftGenerationRegistrationQueryResult;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraft;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftFacts;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftGeneration;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftImage;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftImageSource;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftImageType;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 생성 회차 claim · 작업 연결 판정과 정규 입력 hash (#359 PR-B1). */
class ProductDraftGenerationProcessorTest {

    private static final Instant NOW = Instant.parse("2026-11-17T03:00:00.123456789Z");
    private static final Instant NOW_MICROS = Instant.parse("2026-11-17T03:00:00.123456Z");
    private static final ProductDraftFacts FACTS = new ProductDraftFacts("린넨 \"저고리\"", 12L, List.of("린넨 100%"), List.of(), "총장 62cm\n가슴 54cm", null,
        List.of(), "차분하게", List.of("여름"), List.of());
    private static final ProductDraftImage IMAGE = new ProductDraftImage("https://cdn.example.com/media/a.png", ProductDraftImageType.THUMBNAIL, 1200, null,
        new ProductDraftImageSource("products/media/7/k.png", "\"etag-1\"", 12L, "image/png"));

    private final ProductDraftRepositoryPort port = mock(ProductDraftRepositoryPort.class);
    private final ProductDraftGenerationPort generationPort = mock(ProductDraftGenerationPort.class);
    private final ProductDraftGenerationProcessor processor = new ProductDraftGenerationProcessor(port, generationPort, Clock.fixed(NOW, ZoneOffset.UTC));

    private static ProductDraft draft(ProductDraftStatus status, Instant expiresAt, List<ProductDraftImage> images, ProductDraftGeneration generation) {
        return new ProductDraft(7L, status, 100L, FACTS, images, null, null, expiresAt, NOW.minusSeconds(60), generation);
    }

    private void stored(ProductDraft draft) {
        given(port.findById(7L)).willReturn(Optional.of(draft));
    }

    private static void assertCode(ThrowingCallable call, ProductDraftErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ProductDraftException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }

    @Nested
    @DisplayName("입력 hash")
    class InputHash {

        @Test
        @DisplayName("정규 JSON 배열(버전 · 사실 9칸 · 이미지 8칸)의 SHA-256 소문자 hex 64자다 — 카테고리는 넣지 않는다")
        void canonicalArray() throws Exception {
            String expectedJson = "[\"product-draft-input/v1\","
                + "[\"린넨 \\\"저고리\\\"\",[\"린넨 100%\"],[],\"총장 62cm\\n가슴 54cm\",null,[],\"차분하게\",[\"여름\"],[]],"
                + "[[0,\"THUMBNAIL\",\"products/media/7/k.png\",\"\\\"etag-1\\\"\",12,\"image/png\",1200,null]]]";
            String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(expectedJson.getBytes(StandardCharsets.UTF_8)));

            String hash = processor.inputHash(draft(ProductDraftStatus.REQUESTED, NOW.plusSeconds(60), List.of(IMAGE), ProductDraftGeneration.NONE));

            assertThat(hash).isEqualTo(expected).matches("[0-9a-f]{64}");
        }

        @Test
        @DisplayName("카테고리만 다르면 같은 hash 이고, ETag 가 다르면 다른 hash 다")
        void sensitivity() {
            ProductDraft base = draft(ProductDraftStatus.REQUESTED, NOW.plusSeconds(60), List.of(IMAGE), ProductDraftGeneration.NONE);
            ProductDraftFacts otherCategory = new ProductDraftFacts(FACTS.nameHint(), 99L, FACTS.materials(), FACTS.careNotes(), FACTS.measurementsNote(),
                FACTS.origin(), FACTS.certifications(), FACTS.tone(), FACTS.mustInclude(), FACTS.mustAvoid());
            ProductDraft category = new ProductDraft(7L, ProductDraftStatus.REQUESTED, 100L, otherCategory, List.of(IMAGE), null, null, NOW.plusSeconds(60), null);
            ProductDraftImage otherEtag = IMAGE.pinned(new ProductDraftImageSource("products/media/7/k.png", "\"etag-2\"", 12L, "image/png"));

            assertThat(processor.inputHash(category)).isEqualTo(processor.inputHash(base));
            assertThat(processor.inputHash(draft(ProductDraftStatus.REQUESTED, NOW.plusSeconds(60), List.of(otherEtag), ProductDraftGeneration.NONE)))
                .isNotEqualTo(processor.inputHash(base));
        }
    }

    @Nested
    @DisplayName("claim")
    class Claim {

        @Test
        @DisplayName("생성 대기 · 회차 0 이면 회차 1 로 시작해 저장한다 — 시작한 어드민 · 마이크로초 시각 · hash, 작업 없음")
        void claims() {
            stored(draft(ProductDraftStatus.REQUESTED, NOW.plusSeconds(60), List.of(IMAGE), ProductDraftGeneration.NONE));

            ProductDraft claimed = processor.claim(7L, 200L);

            ArgumentCaptor<ProductDraft> saved = ArgumentCaptor.forClass(ProductDraft.class);
            verify(port).updateGeneration(saved.capture());
            assertThat(saved.getValue()).isEqualTo(claimed);
            assertThat(claimed.status()).isEqualTo(ProductDraftStatus.REQUESTED);
            assertThat(claimed.generation()).isEqualTo(new ProductDraftGeneration(1, 200L, NOW_MICROS, processor.inputHash(claimed), null));
        }

        @Test
        @DisplayName("이미 시작했고 작업이 없으면 저장 값 그대로 돌려준다 — 다른 어드민이 눌러도 처음 시작한 어드민 · hash 를 바꾸지 않는다")
        void replayKeepsStoredValues() {
            ProductDraftGeneration first = new ProductDraftGeneration(1, 200L, NOW.minusSeconds(5), "f".repeat(64), null);
            stored(draft(ProductDraftStatus.REQUESTED, NOW.plusSeconds(60), List.of(IMAGE), first));

            ProductDraft current = processor.claim(7L, 300L);

            assertThat(current.generation()).isEqualTo(first);
            assertThat(current.awaitingGenerationJob()).isTrue();
            verify(port, never()).updateGeneration(any());
        }

        @Test
        @DisplayName("옛 초안의 고정 이미지 원본 합계가 20MiB 를 넘으면 회차를 소모하지 않는다")
        void oldDraftOverImageLimit() {
            ProductDraftImage oversized = IMAGE.pinned(new ProductDraftImageSource("products/media/7/k.png", "etag", 20L * 1024 * 1024 + 1, "image/png"));
            stored(draft(ProductDraftStatus.REQUESTED, NOW.plusSeconds(60), List.of(oversized), ProductDraftGeneration.NONE));

            assertCode(() -> processor.claim(7L, 200L), ProductDraftErrorCode.IMAGES_TOO_LARGE);
            verify(port, never()).updateGeneration(any());
        }

        @Test
        @DisplayName("거절된 초안에 미연결 회차가 있어도 새 작업을 만들지 않는다")
        void rejectedUnboundDoesNotRegister() {
            ProductDraftGeneration first = new ProductDraftGeneration(1, 200L, NOW.minusSeconds(5), "f".repeat(64), null);
            stored(new ProductDraft(7L, ProductDraftStatus.REJECTED, 100L, FACTS, List.of(IMAGE), 300L, NOW,
                NOW.plusSeconds(60), NOW.minusSeconds(60), first));

            assertCode(() -> processor.claim(7L, 400L), ProductDraftErrorCode.GENERATION_NOT_STARTABLE);
            verify(port, never()).updateGeneration(any());
        }

        @Test
        @DisplayName("생성 중이면 그대로 돌려준다 — 등록하지 않는다")
        void generating() {
            stored(draft(ProductDraftStatus.GENERATING, NOW.plusSeconds(60), List.of(IMAGE), new ProductDraftGeneration(1, 200L, NOW, "f".repeat(64), 9L)));

            ProductDraft current = processor.claim(7L, 300L);

            assertThat(current.status()).isEqualTo(ProductDraftStatus.GENERATING);
            assertThat(current.awaitingGenerationJob()).isFalse();
            verify(port, never()).updateGeneration(any());
        }

        @ParameterizedTest
        @EnumSource(value = ProductDraftStatus.class, names = {"READY", "FAILED", "APPLIED", "REJECTED"})
        @DisplayName("생성 대기 · 생성 중이 아니면 051 이다")
        void notStartable(ProductDraftStatus status) {
            stored(new ProductDraft(7L, status, 100L, FACTS, List.of(IMAGE), status == ProductDraftStatus.REJECTED ? 300L : null,
                status == ProductDraftStatus.REJECTED ? NOW : null, NOW.plusSeconds(60), NOW.minusSeconds(60)));

            assertCode(() -> processor.claim(7L, 200L), ProductDraftErrorCode.GENERATION_NOT_STARTABLE);
            verify(port, never()).updateGeneration(any());
        }

        @Test
        @DisplayName("없음 001 · 만료 022(생성 중이어도) · 원본 미고정 052 이고 저장하지 않는다")
        void refusals() {
            given(port.findById(8L)).willReturn(Optional.empty());
            assertCode(() -> processor.claim(8L, 200L), ProductDraftErrorCode.NOT_FOUND_DRAFT);

            stored(draft(ProductDraftStatus.REQUESTED, NOW, List.of(IMAGE), ProductDraftGeneration.NONE));
            assertCode(() -> processor.claim(7L, 200L), ProductDraftErrorCode.DRAFT_EXPIRED);

            stored(draft(ProductDraftStatus.GENERATING, NOW, List.of(IMAGE), new ProductDraftGeneration(1, 200L, NOW, "f".repeat(64), 9L)));
            assertCode(() -> processor.claim(7L, 200L), ProductDraftErrorCode.DRAFT_EXPIRED);

            ProductDraftImage unpinned = new ProductDraftImage("https://cdn.example.com/media/b.png", ProductDraftImageType.GALLERY, null, null);
            stored(draft(ProductDraftStatus.REQUESTED, NOW.plusSeconds(60), List.of(IMAGE, unpinned), ProductDraftGeneration.NONE));
            assertCode(() -> processor.claim(7L, 200L), ProductDraftErrorCode.GENERATION_SOURCE_MISSING);

            verify(port, never()).updateGeneration(any());
        }
    }

    @Nested
    @DisplayName("작업 연결")
    class Bind {

        private static final String HASH = "f".repeat(64);
        private final ProductDraftGeneration claimed = new ProductDraftGeneration(1, 200L, NOW_MICROS, HASH, null);

        private ProductDraft registered() {
            return draft(ProductDraftStatus.REQUESTED, NOW.plusSeconds(60), List.of(IMAGE), claimed);
        }

        @Test
        @DisplayName("DB 에서 다시 읽은 초안에 작업을 붙여 생성 중으로 저장한다")
        void binds() {
            stored(registered());

            ProductDraft bound = processor.bind(registered(), 9L);

            verify(port).updateGeneration(bound);
            assertThat(bound.status()).isEqualTo(ProductDraftStatus.GENERATING);
            assertThat(bound.generation().jobId()).isEqualTo(9L);
            assertThat(bound.generation().requestedByMemberId()).isEqualTo(200L);
        }

        @Test
        @DisplayName("같은 작업이 이미 붙어 있으면 그대로 돌려준다 — 다른 작업이면 계약 위반 504 다")
        void alreadyBound() {
            stored(draft(ProductDraftStatus.GENERATING, NOW.plusSeconds(60), List.of(IMAGE), new ProductDraftGeneration(1, 200L, NOW_MICROS, HASH, 9L)));

            assertThat(processor.bind(registered(), 9L).generation().jobId()).isEqualTo(9L);
            assertCode(() -> processor.bind(registered(), 10L),
                ProductDraftErrorCode.GENERATION_CONTRACT_VIOLATION);
            verify(port, never()).updateGeneration(any());
        }

        @Test
        @DisplayName("등록 결과의 초안 · 회차 · hash 가 보낸 값과 다르면 504 이고 초안을 읽지도 바꾸지도 않는다")
        void registrationMismatch() {
            for (ProductDraftGenerationRegistrationQueryResult wrong : List.of(
                new ProductDraftGenerationRegistrationQueryResult(8L, 1, 9L, HASH),
                new ProductDraftGenerationRegistrationQueryResult(7L, 2, 9L, HASH),
                new ProductDraftGenerationRegistrationQueryResult(7L, 1, 9L, "e".repeat(64)),
                new ProductDraftGenerationRegistrationQueryResult(7L, 1, 0L, HASH)
            )) {
                given(generationPort.register(any())).willReturn(wrong);
                assertCode(() -> processor.register(registered()), ProductDraftErrorCode.GENERATION_CONTRACT_VIOLATION);
            }
            verify(port, never()).findById(anyLong());
            verify(port, never()).updateGeneration(any());
        }

        @Test
        @DisplayName("그 사이 거절됐어도 같은 작업 ID 를 기록하고 거절 상태를 보존한다. 회차가 바뀌면 025 다")
        void changedMeanwhile() {
            stored(new ProductDraft(7L, ProductDraftStatus.REJECTED, 100L, FACTS, List.of(IMAGE), 300L, NOW, NOW.plusSeconds(60), NOW.minusSeconds(60), claimed));
            ProductDraft rejected = processor.bind(registered(), 9L);
            assertThat(rejected.status()).isEqualTo(ProductDraftStatus.REJECTED);
            assertThat(rejected.generation().jobId()).isEqualTo(9L);

            stored(draft(ProductDraftStatus.REQUESTED, NOW.plusSeconds(60), List.of(IMAGE), new ProductDraftGeneration(2, 300L, NOW_MICROS, HASH, null)));
            assertCode(() -> processor.bind(registered(), 9L), ProductDraftErrorCode.DRAFT_BUSY);
            verify(port).updateGeneration(rejected);
        }
    }

    @Test
    @DisplayName("claim · 작업 연결은 각각 짧은 쓰기 트랜잭션이다(REQUIRED) — 원격 등록은 둘 사이 트랜잭션 밖에서 한다")
    void shortWriteTransactions() throws NoSuchMethodException {
        assertThat(ProductDraftGenerationProcessor.class.getMethod("claim", long.class, long.class).getAnnotation(Transactional.class).propagation())
            .isEqualTo(Propagation.REQUIRES_NEW);
        assertThat(ProductDraftGenerationProcessor.class.getMethod("bind", ProductDraft.class, long.class)
            .getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }
}
