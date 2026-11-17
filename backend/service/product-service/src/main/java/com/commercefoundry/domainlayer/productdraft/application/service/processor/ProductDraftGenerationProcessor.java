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
 * 첫 claim 때 한 번 계산해 저장하고, 같은 회차의 재시도는 저장한 값을 보낸다. 선례: {@code SizeChartDraftValidationProcessor} 의 hash.
 */
@Component
@RequiredArgsConstructor
public class ProductDraftGenerationProcessor {

    static final String INPUT_VERSION = "product-draft-input/v1";
    /** 정규 JSON 은 Jackson 기본값으로 쓴다 — 서비스 Jackson 설정이 바뀌어도 hash 가 흔들리지 않게 자체 매퍼다. */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ProductDraftRepositoryPort productDraftRepositoryPort;
    private final ProductDraftGenerationPort productDraftGenerationPort;
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
        long imageBytes = draft.images().stream().mapToLong(image -> image.source().length()).sum();
        if (imageBytes > ProductDraftImageProcessor.MAX_TOTAL_IMAGE_BYTES) {
            throw new ProductDraftException(ProductDraftErrorCode.IMAGES_TOO_LARGE);
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
