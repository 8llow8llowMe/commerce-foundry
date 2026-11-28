package com.commercefoundry.domainlayer.productdraft.application.service.processor;

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
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftStatus;
import com.commercefoundry.global.properties.ProductDraftProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import tools.jackson.databind.json.JsonMapper;

/**
 * AI 상품 초안 생성 회차의 시작(claim) · 작업 연결(bind)과 입력 hash (#359 PR-B1). 두 쓰기는 각각 짧은 쓰기 트랜잭션이고 원격 등록은 그 사이, 트랜잭션 밖에서 한다
 * ({@code ProductDraftAdminFacade}). 상태는 domain 메서드({@link ProductDraft#claimGeneration} · {@link ProductDraft#bindJob})로만 바꾸고 행의 {@code @Version} 이
 * 동시 변경을 막는다 — 낙관적 락 예외는 호출한 쪽이 409 {@code PRODUCT_DRAFT_025} 로 바꾼다.
 *
 * <p><b>입력 hash</b> — 정규 JSON 배열 {@code ["product-draft-input/v1", [사실 9칸], [[index, imageType, key, etag, length, contentType, width, height]…]]} 의
 * UTF-8 SHA-256 소문자 hex 64자다. 카테고리는 넣지 않는다(생성 입력이 아니다). 저장된 값을 다시 정규화하지 않는다 — 저장 단계가 이미 정규화했다.
 * 회차를 시작할 때 한 번 계산해 저장하고, 같은 회차의 재시도는 저장한 값을 보낸다. 선례: {@code SizeChartDraftValidationProcessor} 의 hash.
 *
 * <p><b>매장 전체 동시 생성 (#359 B3, D13)</b> — 새 회차를 시작하는 claim(첫 회차 · 재생성)은 같은 트랜잭션에서 단일 게이트 행을 쓰기 잠근 뒤, 자리를 차지한 초안을 세어
 * {@value #MAX_CONCURRENT_GENERATIONS} 건 이상이면 409 {@code PRODUCT_DRAFT_053} 이다. 자리는 회차를 시작한 생성 대기 · 생성 중이면서 시작한 지
 * {@code product-draft.generation-slot-ttl}(기본 30분)이 안 된 초안이다 — 결과 반영이 운영자 refresh 로만 일어나 방치된 초안이 자리를 오래 막지 않게 한다.
 * 잠금이 새 회차 시작을 줄 세우므로 동시에 넷이 와도 셋을 넘지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductDraftGenerationProcessor {

    static final String INPUT_VERSION = "product-draft-input/v1";
    /** 매장 전체에서 동시에 진행할 수 있는 생성 수(#359 D9 · D13). */
    static final int MAX_CONCURRENT_GENERATIONS = 3;
    /** 정규 JSON 은 Jackson 기본값으로 쓴다 — 서비스 Jackson 설정이 바뀌어도 hash 가 흔들리지 않게 자체 매퍼다. */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ProductDraftRepositoryPort productDraftRepositoryPort;
    private final ProductDraftGenerationPort productDraftGenerationPort;
    private final ProductDraftProperties productDraftProperties;
    private final Clock clock;

    /** 원격 등록은 Facade 의 비트랜잭션 흐름에서 호출한다. 응답 계약을 검사한 뒤 작업 아이디만 돌려준다. */
    public long register(ProductDraft draft) {
        ProductDraftGenerationRegistrationQueryResult registration = productDraftGenerationPort.register(draft);
        ProductDraftGeneration sent = draft.generation();
        if (registration.draftId() != draft.id() || registration.generationNo() != sent.generationNo()
            || !sent.inputHash().equals(registration.inputHash()) || registration.jobId() <= 0) {
            throw new ProductDraftException(ProductDraftErrorCode.GENERATION_CONTRACT_VIOLATION);
        }
        return registration.jobId();
    }

    /**
     * 생성 회차를 시작하거나, 이미 시작한 초안을 그대로 돌려준다(DB 에서 읽은 값). 돌려준 초안이 {@link ProductDraft#awaitingGenerationJob()} 이면 호출한 쪽이 등록한다.
     * <ul>
     *   <li>생성 대기 · 회차 0 → 회차 1 로 claim(시작한 어드민 · 시각 · hash 저장)</li>
     *   <li>생성 대기 · 회차 시작함 · 작업 없음 → 그대로(같은 값으로 재등록 — 다른 어드민이 눌러도 처음 시작한 어드민을 보낸다)</li>
     *   <li>생성 중 → 그대로(이미 시작함)</li>
     * </ul>
     * 없음 {@code 001}(404) · 만료 {@code 022} · 그 밖의 상태 {@code 051} · 이미지 원본 미고정 {@code 052}(409).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ProductDraft claim(long draftId, long actorMemberId) {
        Instant now = clock.instant();
        ProductDraft draft = productDraftRepositoryPort.findById(draftId).orElseThrow(() -> new ProductDraftException(ProductDraftErrorCode.NOT_FOUND_DRAFT));
        ProductDraftStatus current = draft.statusAt(now);
        if (current == ProductDraftStatus.EXPIRED) {
            throw new ProductDraftException(ProductDraftErrorCode.DRAFT_EXPIRED);
        }
        if (current == ProductDraftStatus.GENERATING) {
            return draft;
        }
        if (current != ProductDraftStatus.REQUESTED) {
            throw new ProductDraftException(ProductDraftErrorCode.GENERATION_NOT_STARTABLE);
        }
        if (draft.generation().claimed()) {
            return draft;
        }
        if (!draft.imagesPinned()) {
            throw new ProductDraftException(ProductDraftErrorCode.GENERATION_SOURCE_MISSING);
        }
        return claimNewGeneration(draft, actorMemberId, now);
    }

    /**
     * 재생성(#359 B3) — {@code expectedGenerationNo} 는 운영자가 보고 있던 회차다. 같은 요청을 다시 보내도 회차가 한 번만 오른다.
     * <ul>
     *   <li>지금 회차 = 기대 회차 · 검토 대기 또는 다시 생성할 수 있는 실패 · 남은 회차 있음 → 다음 회차로 claim(게이트 통과 필요)</li>
     *   <li>지금 회차 = 기대 회차 + 1 · 생성 대기(시작함) · 생성 중 → 그대로(같은 요청의 재시도 — 작업이 없으면 호출한 쪽이 저장한 값으로 다시 등록한다)</li>
     * </ul>
     * 없음 {@code 001}(404) · 만료 {@code 022} · 회차 불일치 {@code 025} · 그 밖의 상태 {@code 051} · 원본 미고정 {@code 052} · 자리 없음 {@code 053} ·
     * 횟수 소진 {@code 054} · 새 초안 필요 {@code 055}(409).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ProductDraft claimRegeneration(long draftId, long actorMemberId, int expectedGenerationNo) {
        Instant now = clock.instant();
        ProductDraft draft = productDraftRepositoryPort.findById(draftId).orElseThrow(() -> new ProductDraftException(ProductDraftErrorCode.NOT_FOUND_DRAFT));
        ProductDraftStatus current = draft.statusAt(now);
        if (current == ProductDraftStatus.EXPIRED) {
            throw new ProductDraftException(ProductDraftErrorCode.DRAFT_EXPIRED);
        }
        int generationNo = draft.generation().generationNo();
        if (generationNo == expectedGenerationNo + 1
            && (current == ProductDraftStatus.GENERATING || (current == ProductDraftStatus.REQUESTED && draft.generation().claimed()))) {
            return draft;
        }
        if (generationNo != expectedGenerationNo) {
            throw new ProductDraftException(ProductDraftErrorCode.DRAFT_BUSY);
        }
        if (current != ProductDraftStatus.READY && current != ProductDraftStatus.FAILED) {
            throw new ProductDraftException(ProductDraftErrorCode.GENERATION_NOT_STARTABLE);
        }
        if (current == ProductDraftStatus.FAILED && draft.requiresNewDraft()) {
            throw new ProductDraftException(ProductDraftErrorCode.REGENERATION_NEW_DRAFT_REQUIRED);
        }
        if (generationNo >= ProductDraftGeneration.MAX_GENERATION_NO) {
            throw new ProductDraftException(ProductDraftErrorCode.REGENERATION_EXHAUSTED);
        }
        if (!draft.imagesPinned()) {
            throw new ProductDraftException(ProductDraftErrorCode.GENERATION_SOURCE_MISSING);
        }
        return claimNewGeneration(draft, actorMemberId, now);
    }

    /**
     * 새 회차 시작 — 매장 전체 게이트 행을 잠근 뒤 자리를 세고(D13), 남았으면 claim 한다. 잠금은 이 트랜잭션이 끝날 때 풀린다. 같은 회차의 재등록은 여기 오지 않는다
     * (자리는 그 회차를 시작할 때 이미 잡았다). 게이트 행이 없으면 운영 DDL 누락이라 {@code 502}(503)다.
     */
    private ProductDraft claimNewGeneration(ProductDraft draft, long actorMemberId, Instant now) {
        long imageBytes = draft.images().stream().mapToLong(image -> image.source().length()).sum();
        if (imageBytes > ProductDraftImageProcessor.MAX_TOTAL_IMAGE_BYTES) {
            throw new ProductDraftException(ProductDraftErrorCode.IMAGES_TOO_LARGE);
        }
        if (!productDraftRepositoryPort.lockGenerationGate()) {
            log.error("product draft generation gate row is missing. draftId={}", draft.id());
            throw new ProductDraftException(ProductDraftErrorCode.GENERATION_UNAVAILABLE);
        }
        if (productDraftRepositoryPort.countGenerationSlots(now, now.minus(productDraftProperties.generationSlotTtl())) >= MAX_CONCURRENT_GENERATIONS) {
            throw new ProductDraftException(ProductDraftErrorCode.GENERATION_SLOTS_FULL);
        }
        ProductDraft claimed = draft.claimGeneration(actorMemberId, inputHash(draft), now.truncatedTo(ChronoUnit.MICROS));
        productDraftRepositoryPort.updateGeneration(claimed);
        return claimed;
    }

    /**
     * 등록한 작업을 붙여 생성 중으로 바꾼다. DB 에서 다시 읽어 판정한다.
     * <ul>
     *   <li>등록 결과의 초안 · 회차 · hash 가 등록에 쓴 초안과 다르면 계약 위반 {@code 504}(502) — 초안은 그대로</li>
     *   <li>같은 작업이 이미 붙어 있으면 그대로(동시 재시도가 먼저 붙였다). 다른 작업이 붙어 있으면 {@code 504}</li>
     *   <li>그 사이 회차가 바뀌었거나 생성 대기가 아니면 {@code 025}(409)</li>
     * </ul>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ProductDraft bind(ProductDraft registered, long jobId) {
        ProductDraftGeneration sent = registered.generation();
        if (jobId <= 0) {
            throw new ProductDraftException(ProductDraftErrorCode.GENERATION_CONTRACT_VIOLATION);
        }
        ProductDraft current = productDraftRepositoryPort.findById(registered.id())
            .orElseThrow(() -> new ProductDraftException(ProductDraftErrorCode.NOT_FOUND_DRAFT));
        ProductDraftGeneration stored = current.generation();
        if (stored.generationNo() != sent.generationNo() || !sent.inputHash().equals(stored.inputHash())) {
            throw new ProductDraftException(ProductDraftErrorCode.DRAFT_BUSY);
        }
        if (stored.jobId() != null) {
            if (stored.jobId() != jobId) {
                throw new ProductDraftException(ProductDraftErrorCode.GENERATION_CONTRACT_VIOLATION);
            }
            return current;
        }
        if (!current.canBindGenerationJob()) {
            throw new ProductDraftException(ProductDraftErrorCode.DRAFT_BUSY);
        }
        ProductDraft bound = current.bindJob(registered.id(), sent.generationNo(), sent.inputHash(), jobId);
        productDraftRepositoryPort.updateGeneration(bound);
        return bound;
    }

    /** 정규 입력 hash — 클래스 설명의 배열 모양. 이미지 원본을 고정하지 않은 초안은 호출 순서 버그다. */
    public String inputHash(ProductDraft draft) {
        ProductDraftFacts facts = draft.facts();
        List<Object> factValues = Arrays.asList(facts.nameHint(), facts.materials(), facts.careNotes(), facts.measurementsNote(), facts.origin(),
            facts.certifications(), facts.tone(), facts.mustInclude(), facts.mustAvoid());
        List<ProductDraftImage> images = draft.images();
        List<Object> imageValues = IntStream.range(0, images.size()).<Object>mapToObj(index -> {
            ProductDraftImage image = images.get(index);
            ProductDraftImageSource source = image.source();
            if (source == null) {
                throw new IllegalStateException("이미지 원본을 고정하지 않은 초안은 hash 를 만들지 않습니다. draftId=" + draft.id());
            }
            return Arrays.asList(index, image.imageType().name(), source.key(), source.eTag(), source.length(), source.contentType(), image.width(), image.height());
        }).toList();
        return sha256(JSON.writeValueAsString(List.of(INPUT_VERSION, factValues, imageValues)));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 을 쓸 수 없습니다.");
        }
    }
}
