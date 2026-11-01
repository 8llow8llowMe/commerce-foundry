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
 * 공개 주소 밖({@code 030}). 이미지가 없으면 주소 설정을 보지 않는다 — 사실만으로도 초안을 만들 수 있다.
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

    /** 이미지를 판정하고 생성 대기 초안을 저장한다. 만료 시각 = 지금 + {@code product-draft.ttl}. */
    @Transactional(propagation = Propagation.MANDATORY)
    public ProductDraft create(ProductDraftCreateCommand command) {
        validateImages(command.images());
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

    private void validateImages(List<ProductDraftImage> images) {
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
}
