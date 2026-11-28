package com.commercefoundry.domainlayer.productdraft.application.service.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
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
import com.commercefoundry.global.properties.ProductDraftProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
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
    private static final Duration SLOT_TTL = Duration.ofMinutes(30);
    private static final ProductDraftFacts FACTS = new ProductDraftFacts("린넨 \"저고리\"", 12L, List.of("린넨 100%"), List.of(), "총장 62cm\n가슴 54cm", null,
        List.of(), "차분하게", List.of("여름"), List.of());
    private static final ProductDraftImage IMAGE = new ProductDraftImage("https://cdn.example.com/media/a.png", ProductDraftImageType.THUMBNAIL, 1200, null,
        new ProductDraftImageSource("products/media/7/k.png", "\"etag-1\"", 12L, "image/png"));

    private final ProductDraftRepositoryPort port = mock(ProductDraftRepositoryPort.class);
    private final ProductDraftGenerationPort generationPort = mock(ProductDraftGenerationPort.class);
    private final ProductDraftGenerationProcessor processor = new ProductDraftGenerationProcessor(port, generationPort,
        new ProductDraftProperties(Duration.ofDays(7), true, SLOT_TTL), Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void openGate() {
        given(port.lockGenerationGate()).willReturn(true);
    }

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
    @DisplayName("매장 전체 동시 생성 게이트 (B3, D13)")
    class Gate {

        @Test
        @DisplayName("게이트를 잠근 뒤 자리를 센다 — 기준은 지금과 자리 시간(30분) 전이고, 2건이면 시작한다")
        void countsAfterLock() {
            stored(draft(ProductDraftStatus.REQUESTED, NOW.plusSeconds(60), List.of(IMAGE), ProductDraftGeneration.NONE));
            given(port.countGenerationSlots(NOW, NOW.minus(SLOT_TTL))).willReturn(2L);

            processor.claim(7L, 200L);

            var order = inOrder(port);
            order.verify(port).lockGenerationGate();
            order.verify(port).countGenerationSlots(NOW, NOW.minus(SLOT_TTL));
            order.verify(port).updateGeneration(any());
        }

        @Test
        @DisplayName("자리가 3건 찼으면 053 이고 초안을 바꾸지 않는다")
        void full() {
            stored(draft(ProductDraftStatus.REQUESTED, NOW.plusSeconds(60), List.of(IMAGE), ProductDraftGeneration.NONE));
            given(port.countGenerationSlots(any(), any())).willReturn(3L);

            assertCode(() -> processor.claim(7L, 200L), ProductDraftErrorCode.GENERATION_SLOTS_FULL);
            verify(port, never()).updateGeneration(any());
        }

        @Test
        @DisplayName("게이트 행이 없으면(운영 DDL insert 누락) 502 이고 초안을 바꾸지 않는다")
        void missingGateRow() {
            stored(draft(ProductDraftStatus.REQUESTED, NOW.plusSeconds(60), List.of(IMAGE), ProductDraftGeneration.NONE));
            given(port.lockGenerationGate()).willReturn(false);

            assertCode(() -> processor.claim(7L, 200L), ProductDraftErrorCode.GENERATION_UNAVAILABLE);
            verify(port, never()).countGenerationSlots(any(), any());
            verify(port, never()).updateGeneration(any());
        }

        @Test
        @DisplayName("이미 시작한 회차의 재등록 · 생성 중 재시도는 게이트를 지나지 않는다 — 자리는 회차를 시작할 때 잡았다")
        void replayDoesNotTakeSlot() {
            stored(draft(ProductDraftStatus.REQUESTED, NOW.plusSeconds(60), List.of(IMAGE), new ProductDraftGeneration(1, 200L, NOW_MICROS, "f".repeat(64), null)));
            processor.claim(7L, 300L);
            stored(draft(ProductDraftStatus.REQUESTED, NOW.plusSeconds(60), List.of(IMAGE), new ProductDraftGeneration(2, 200L, NOW_MICROS, "f".repeat(64), null)));
            processor.claimRegeneration(7L, 300L, 1);

            verify(port, never()).lockGenerationGate();
            verify(port, never()).countGenerationSlots(any(), any());
        }
    }

    @Nested
    @DisplayName("재생성 (B3)")
    class Regenerate {

        private static final String HASH = "f".repeat(64);

        private ProductDraft finished(ProductDraftStatus status, int generationNo, String failureCode, Instant expiresAt) {
            return new ProductDraft(7L, status, 100L, FACTS, List.of(IMAGE), null, null, expiresAt, NOW.minusSeconds(60),
                new ProductDraftGeneration(generationNo, 200L, NOW_MICROS.minusSeconds(600), HASH, 9L), null, failureCode);
        }

        @Test
        @DisplayName("검토 대기 · 지금 회차가 맞으면 다음 회차로 시작한다 — 생성 대기, 새 어드민 · 시각, 작업 없음, 이전 결과 없음")
        void fromReady() {
            stored(finished(ProductDraftStatus.READY, 1, null, NOW.plusSeconds(60)));

            ProductDraft claimed = processor.claimRegeneration(7L, 300L, 1);

            verify(port).updateGeneration(claimed);
            assertThat(claimed.status()).isEqualTo(ProductDraftStatus.REQUESTED);
            assertThat(claimed.generation()).isEqualTo(new ProductDraftGeneration(2, 300L, NOW_MICROS, processor.inputHash(claimed), null));
            assertThat(claimed.candidate()).isNull();
            assertThat(claimed.failureCode()).isNull();
            assertThat(claimed.awaitingGenerationJob()).isTrue();
        }

        @Test
        @DisplayName("다시 만들 수 있는 실패(AI 오류 · 취소 · 한도 초과)도 다음 회차로 시작한다")
        void fromRetryableFailure() {
            for (String code : List.of("PRODUCT_DRAFT_AI_FAILED", ProductDraft.GENERATION_CANCELLED, "PRODUCT_DRAFT_AI_BUDGET_EXCEEDED", "RUN_TIMEOUT")) {
                stored(finished(ProductDraftStatus.FAILED, 2, code, NOW.plusSeconds(60)));
                assertThat(processor.claimRegeneration(7L, 300L, 2).generation().generationNo()).isEqualTo(3);
            }
        }

        @Test
        @DisplayName("같은 요청의 재시도(이미 다음 회차 생성 대기 · 생성 중)는 그대로 돌려주고 회차를 다시 올리지 않는다")
        void retryIsIdempotent() {
            ProductDraft waiting = draft(ProductDraftStatus.REQUESTED, NOW.plusSeconds(60), List.of(IMAGE), new ProductDraftGeneration(2, 300L, NOW_MICROS, HASH, null));
            stored(waiting);
            assertThat(processor.claimRegeneration(7L, 400L, 1)).isEqualTo(waiting);

            ProductDraft generating = draft(ProductDraftStatus.GENERATING, NOW.plusSeconds(60), List.of(IMAGE), new ProductDraftGeneration(2, 300L, NOW_MICROS, HASH, 10L));
            stored(generating);
            assertThat(processor.claimRegeneration(7L, 400L, 1)).isEqualTo(generating);

            verify(port, never()).updateGeneration(any());
        }

        @Test
        @DisplayName("회차가 화면과 다르면 025 다 — 오래된 화면의 재생성이 회차를 하나 더 쓰지 않는다")
        void staleGenerationNo() {
            stored(finished(ProductDraftStatus.READY, 2, null, NOW.plusSeconds(60)));
            assertCode(() -> processor.claimRegeneration(7L, 300L, 1), ProductDraftErrorCode.DRAFT_BUSY);

            stored(finished(ProductDraftStatus.READY, 1, null, NOW.plusSeconds(60)));
            assertCode(() -> processor.claimRegeneration(7L, 300L, 2), ProductDraftErrorCode.DRAFT_BUSY);
            verify(port, never()).updateGeneration(any());
        }

        @Test
        @DisplayName("3회차를 다 썼으면 054, 새 초안이 필요한 실패면 055, 만료면 022 다")
        void refusals() {
            stored(finished(ProductDraftStatus.READY, 3, null, NOW.plusSeconds(60)));
            assertCode(() -> processor.claimRegeneration(7L, 300L, 3), ProductDraftErrorCode.REGENERATION_EXHAUSTED);

            stored(finished(ProductDraftStatus.FAILED, 3, "PRODUCT_DRAFT_AI_FAILED", NOW.plusSeconds(60)));
            assertCode(() -> processor.claimRegeneration(7L, 300L, 3), ProductDraftErrorCode.REGENERATION_EXHAUSTED);

            for (String code : List.of("PRODUCT_DRAFT_IMAGE_CHANGED", "PRODUCT_DRAFT_INPUT_EXPIRED", ProductDraft.GENERATION_RESULT_EXPIRED,
                "PRODUCT_DRAFT_INPUT_REJECTED")) {
                stored(finished(ProductDraftStatus.FAILED, 1, code, NOW.plusSeconds(60)));
                assertCode(() -> processor.claimRegeneration(7L, 300L, 1), ProductDraftErrorCode.REGENERATION_NEW_DRAFT_REQUIRED);
            }

            stored(finished(ProductDraftStatus.READY, 1, null, NOW));
            assertCode(() -> processor.claimRegeneration(7L, 300L, 1), ProductDraftErrorCode.DRAFT_EXPIRED);
            verify(port, never()).updateGeneration(any());
        }

        @Test
        @DisplayName("생성 중 · 적용 · 거절 초안은 051 이다 — 이전 회차가 끝나기 전에 다음 회차를 열지 않는다")
        void notFinished() {
            stored(draft(ProductDraftStatus.GENERATING, NOW.plusSeconds(60), List.of(IMAGE), new ProductDraftGeneration(1, 200L, NOW_MICROS, HASH, 9L)));
            assertCode(() -> processor.claimRegeneration(7L, 300L, 1), ProductDraftErrorCode.GENERATION_NOT_STARTABLE);

            stored(new ProductDraft(7L, ProductDraftStatus.REJECTED, 100L, FACTS, List.of(IMAGE), 300L, NOW, NOW.plusSeconds(60), NOW.minusSeconds(60),
                new ProductDraftGeneration(1, 200L, NOW_MICROS, HASH, 9L)));
            assertCode(() -> processor.claimRegeneration(7L, 300L, 1), ProductDraftErrorCode.GENERATION_NOT_STARTABLE);
            verify(port, never()).updateGeneration(any());
        }

        @Test
        @DisplayName("재생성도 게이트를 지난다 — 자리가 찼으면 053 이고 이전 결과를 지우지 않는다")
        void gated() {
            stored(finished(ProductDraftStatus.READY, 1, null, NOW.plusSeconds(60)));
            given(port.countGenerationSlots(any(), any())).willReturn(3L);

            assertCode(() -> processor.claimRegeneration(7L, 300L, 1), ProductDraftErrorCode.GENERATION_SLOTS_FULL);
            verify(port).lockGenerationGate();
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
        assertThat(ProductDraftGenerationProcessor.class.getMethod("claimRegeneration", long.class, long.class, int.class)
            .getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }
}
