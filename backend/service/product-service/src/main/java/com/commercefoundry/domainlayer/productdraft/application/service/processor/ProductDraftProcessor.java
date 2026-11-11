package com.commercefoundry.domainlayer.productdraft.application.service.processor;

import com.commercefoundry.domainlayer.productdraft.application.command.ProductDraftCreateCommand;
import com.commercefoundry.domainlayer.productdraft.application.exception.ProductDraftErrorCode;
import com.commercefoundry.domainlayer.productdraft.application.exception.ProductDraftException;
import com.commercefoundry.domainlayer.productdraft.application.port.out.ProductDraftRepositoryPort;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraft;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftImage;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftImageType;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftStatus;
import com.commercefoundry.global.media.MediaUrlPolicy;
import com.commercefoundry.global.properties.ProductDraftProperties;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 초안 읽기 · 쓰기 · 판정. 시각은 {@link Clock} 으로 한 번 잡아 만료 판정과 저장에 같이 쓴다.
 *
 * <p>이미지 판정 순서: 대표 이미지 두 장 이상({@code PRODUCT_DRAFT_034}) → 같은 주소 두 번({@code 035}) → 미디어 저장소 미설정({@code 032}, 503) →
 * 공개 주소 밖({@code 030}) — 여기까지가 {@link #validateImages}(I/O 없음)이고, 그 뒤 관리 키 · 저장소 확인({@code 031} · {@code 033} · {@code 036} · {@code 501})은
 * {@link ProductDraftImageProcessor#pin} 이 트랜잭션 밖에서 한다. 이미지가 없으면 주소 설정을 보지 않는다 — 사실만으로도 초안을 만들 수 있다.
 */
@Component
@RequiredArgsConstructor
public class ProductDraftProcessor {

    private final ProductDraftRepositoryPort productDraftRepositoryPort;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final MediaUrlPolicy mediaUrlPolicy;
    private final ProductDraftProperties productDraftProperties;
    private final Clock clock;

    public Instant now() {
        return clock.instant();
    }

    /** 없으면 {@code PRODUCT_DRAFT_001}(404)이다. */
    public ProductDraft getDraft(long draftId) {
        return productDraftRepositoryPort.findById(draftId).orElseThrow(() -> new ProductDraftException(ProductDraftErrorCode.NOT_FOUND_DRAFT));
    }

    /** 저장소를 부르지 않는 이미지 판정({@code 034} → {@code 035} → {@code 032} → {@code 030}). 키 고정 전에 부른다. */
    public void validateImages(List<ProductDraftImage> images) {
        if (images.isEmpty()) {
            return;
        }
        if (images.stream().filter(image -> image.imageType() == ProductDraftImageType.THUMBNAIL).count() > 1) {
            throw new ProductDraftException(ProductDraftErrorCode.THUMBNAIL_DUPLICATED);
        }
        Set<String> urls = new HashSet<>();
        if (!images.stream().allMatch(image -> urls.add(image.url()))) {
            throw new ProductDraftException(ProductDraftErrorCode.IMAGE_DUPLICATED);
        }
        if (!mediaUrlPolicy.isConfigured()) {
            throw new ProductDraftException(ProductDraftErrorCode.MEDIA_STORAGE_NOT_CONFIGURED);
        }
        if (!images.stream().allMatch(image -> mediaUrlPolicy.isAllowed(image.url()))) {
            throw new ProductDraftException(ProductDraftErrorCode.IMAGE_URL_NOT_ALLOWED);
        }
    }

    /**
     * 생성 대기 초안을 저장한다. 만료 시각 = 지금 + {@code product-draft.ttl}. 이미지는 {@link ProductDraftImageProcessor#pin} 으로 원본을 고정한 것만 받는다 —
     * 고정하지 않은 이미지가 오면 호출 순서 버그다. 저장소 확인을 마친 뒤 이 짧은 쓰기 트랜잭션만 연다.
     */
    @Transactional
    public ProductDraft create(ProductDraftCreateCommand command) {
        if (command.images().stream().anyMatch(image -> image.source() == null)) {
            throw new IllegalStateException("원본을 고정하지 않은 초안 이미지는 저장하지 않습니다.");
        }
        Instant now = now();
        ProductDraft draft = ProductDraft.request(
            snowflakeIdGenerator.generateId(), command.requestedByMemberId(), command.facts(), command.images(), now.plus(productDraftProperties.ttl())
        );
        return productDraftRepositoryPort.insert(draft);
    }

    /**
     * 거절. 이미 거절한 초안이면 그대로 돌려준다(같은 요청 재전송 — 처음 거절한 어드민 · 시각을 바꾸지 않는다).
     * 적용한 초안은 {@code PRODUCT_DRAFT_020}, 보관 기간이 지났으면 {@code 022}(409)다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ProductDraft reject(long draftId, long memberId, Instant now) {
        ProductDraft draft = getDraft(draftId);
        ProductDraftStatus current = draft.statusAt(now);
        if (current == ProductDraftStatus.REJECTED) {
            return draft;
        }
        if (current == ProductDraftStatus.EXPIRED) {
            throw new ProductDraftException(ProductDraftErrorCode.DRAFT_EXPIRED);
        }
        if (!current.isOpen()) {
            throw new ProductDraftException(ProductDraftErrorCode.DRAFT_NOT_REJECTABLE);
        }
        ProductDraft rejected = draft.reject(memberId, now);
        productDraftRepositoryPort.updateDecision(rejected);
        return rejected;
    }

    /**
     * operations 생성 작업이 읽을 초안 (internal). 생성 대기 · 생성 중이고 모든 이미지의 원본이 고정돼 있어야 한다.
     * 없음 {@code 001}(404) · 만료 {@code 022} · 거절 {@code 041} · 그 밖의 상태 {@code 042} · 원본 미고정(옛 초안) {@code 043}(409).
     * 생성 대기도 받는 것은 등록 응답이 유실돼 상태가 아직 바뀌지 않았을 수 있어서다(사이즈표 선례).
     */
    @Transactional(readOnly = true)
    public ProductDraft generationDraft(long draftId) {
        if (draftId <= 0) {
            throw new ProductDraftException(ProductDraftErrorCode.NOT_FOUND_DRAFT);
        }
        ProductDraft draft = getDraft(draftId);
        ProductDraftStatus current = draft.statusAt(now());
        if (current == ProductDraftStatus.EXPIRED) {
            throw new ProductDraftException(ProductDraftErrorCode.DRAFT_EXPIRED);
        }
        if (current == ProductDraftStatus.REJECTED) {
            throw new ProductDraftException(ProductDraftErrorCode.GENERATION_DRAFT_REJECTED);
        }
        if (current != ProductDraftStatus.REQUESTED && current != ProductDraftStatus.GENERATING) {
            throw new ProductDraftException(ProductDraftErrorCode.GENERATION_STATUS_INVALID);
        }
        if (draft.images().stream().anyMatch(image -> image.source() == null)) {
            throw new ProductDraftException(ProductDraftErrorCode.GENERATION_INPUT_UNAVAILABLE);
        }
        return draft;
    }

    /** 생성 작업이 읽을 이미지 한 장(0부터). 판정은 {@link #generationDraft} 와 같고, 범위 밖 순번은 {@code 045}(404)다. 바이트는 호출한 쪽이 트랜잭션 밖에서 읽는다. */
    @Transactional(readOnly = true)
    public ProductDraftImage generationImage(long draftId, int index) {
        List<ProductDraftImage> images = generationDraft(draftId).images();
        if (index < 0 || index >= images.size()) {
            throw new ProductDraftException(ProductDraftErrorCode.GENERATION_IMAGE_NOT_FOUND);
        }
        return images.get(index);
    }
}
