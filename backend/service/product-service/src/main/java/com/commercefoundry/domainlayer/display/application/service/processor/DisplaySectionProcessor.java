package com.commercefoundry.domainlayer.display.application.service.processor;

import com.commercefoundry.domainlayer.display.application.command.DisplaySectionUpsertCommand;
import com.commercefoundry.domainlayer.display.application.exception.DisplayErrorCode;
import com.commercefoundry.domainlayer.display.application.exception.DisplayException;
import com.commercefoundry.domainlayer.display.application.info.DisplayMainInfo;
import com.commercefoundry.domainlayer.display.application.info.DisplaySectionAdminInfo;
import com.commercefoundry.domainlayer.display.application.port.out.DisplaySectionRepositoryPort;
import com.commercefoundry.domainlayer.display.domain.model.DisplayItem;
import com.commercefoundry.domainlayer.display.domain.model.DisplayItemType;
import com.commercefoundry.domainlayer.display.domain.model.DisplayMedia;
import com.commercefoundry.domainlayer.display.domain.model.DisplaySection;
import com.commercefoundry.domainlayer.display.domain.model.DisplaySectionContent;
import com.commercefoundry.domainlayer.display.domain.model.DisplaySectionType;
import com.commercefoundry.domainlayer.display.domain.model.DisplayViolation;
import com.commercefoundry.domainlayer.product.domain.model.Product;
import com.commercefoundry.domainlayer.product.domain.model.ProductImage;
import com.commercefoundry.domainlayer.product.domain.model.ProductSummary;
import com.commercefoundry.global.media.MediaUrlPolicy;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 메인 전시 섹션 읽기 · 쓰기 · 판정. 섹션 수에 상한({@value #MAX_SECTION_COUNT})이 있어 전체를 한 번에 읽고, 아이템은 섹션 아이디 in 절로 한 번에 읽는다 (N+1 금지).
 *
 * <p>상품 정보는 이 컨텍스트가 읽지 않는다 — Facade 가 상품 컨텍스트 Processor 로 받은 domain model({@link ProductSummary} · {@link Product})을
 * 인자로 넘긴다 ({@code architecture-guide.md} §3 컨텍스트 간 의존 규칙, 의존 방향 display → product).
 *
 * <p>쓰기 메서드는 판정을 다시 하지 않는다. Facade 가 {@link #lockAll()} 로 잠근 섹션을 받아 판정 메서드를 부른 뒤 부른다 — 잠금 · 쓰기 메서드는 {@code MANDATORY} 다.
 */
@Component
@RequiredArgsConstructor
public class DisplaySectionProcessor {

    /** 섹션 수 상한. 메인 한 화면의 블록 수로 넉넉하고, 전체 잠금 · 전체 읽기의 비용을 작게 묶는다. */
    public static final int MAX_SECTION_COUNT = 50;

    /** 판정용 초안의 임시 아이디 — 저장할 때 Snowflake 로 바꾼다. */
    private static final long DRAFT_ID = 0L;

    private final DisplaySectionRepositoryPort displaySectionRepositoryPort;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final MediaUrlPolicy mediaUrlPolicy;
    private final Clock clock;

    // ── 읽기 ────────────────────────────────────────────────────

    /** 고객 메인 — 지금 보이는 섹션({@link DisplaySection#isLiveAt})과 그 아이템. 섹션 1번 · 아이템 1번(보이는 섹션만) 읽는다. */
    public List<DisplaySectionContent> getLiveContents() {
        Instant now = clock.instant();
        return withItems(displaySectionRepositoryPort.findAll().stream().filter(section -> section.isLiveAt(now)).toList());
    }

    /** 어드민 목록 — 숨김 · 기간 밖 포함 전체 섹션과 아이템. */
    public List<DisplaySectionContent> getAllContents() {
        return withItems(displaySectionRepositoryPort.findAll());
    }

    /** 없으면 {@code PRODUCT_046}(404)이다. */
    public DisplaySectionContent getContent(long sectionId) {
        DisplaySection section = displaySectionRepositoryPort.findById(sectionId)
            .orElseThrow(() -> new DisplayException(DisplayErrorCode.NOT_FOUND_DISPLAY_SECTION));
        return new DisplaySectionContent(section, displaySectionRepositoryPort.findItemsBySectionIds(List.of(sectionId)));
    }

    /** 섹션들의 상품 아이템이 가리키는 상품 아이디 — 모든 섹션을 합쳐 중복 없이. Facade 가 상품 조회를 한 번에 하게 한다. */
    public Set<Long> productIdsOf(Collection<DisplaySectionContent> contents) {
        Set<Long> productIds = new LinkedHashSet<>();
        contents.forEach(content -> productIds.addAll(content.productIds()));
        return productIds;
    }

    /**
     * 고객 메인 응답 모양. 고객에게 보이지 않는 상품(숨김 · 없음 — {@code visibleProducts} 에 없다)의 아이템은 뺀다.
     *
     * <p><b>상품 픽의 상품이 모두 빠져도 섹션은 남긴다(빈 목록).</b> 섹션 머리(제목 · 링크)는 어드민이 노출하기로 한 것이고, 상품 숨김은 상품 관리의 일이라
     * 전시 구성이 상품 상태에 따라 조용히 바뀌지 않게 한다 — 빈 목록일 때 섹션을 그릴지는 화면이 정한다.
     *
     * @param visibleProducts 고객에게 보이는 상품의 카드 (상품 아이디 → 카드)
     */
    public DisplayMainInfo toMainInfo(List<DisplaySectionContent> contents, Map<Long, ProductSummary> visibleProducts) {
        List<DisplayMainInfo.Section> sections = contents.stream().map(content -> {
            DisplaySection section = content.section();
            List<DisplayMainInfo.Element> elements = new ArrayList<>();
            for (DisplayItem item : content.items()) {
                if (!item.isProduct()) {
                    elements.add(new DisplayMainInfo.Element(item.id(), item.itemType(), item.media(), null, item.caption()));
                    continue;
                }
                ProductSummary summary = visibleProducts.get(item.productId());
                if (summary != null) {
                    elements.add(new DisplayMainInfo.Element(item.id(), item.itemType(), null, toProduct(summary), item.caption()));
                }
            }
            return new DisplayMainInfo.Section(
                section.id(), section.sectionType(), section.title(), section.subtitle(), section.body(),
                section.linkUrl(), section.linkLabel(), List.copyOf(elements)
            );
        }).toList();
        return new DisplayMainInfo(sections);
    }

    /** @param products 상품 아이템이 가리키는 상품 (숨김 포함) */
    public List<DisplaySectionAdminInfo> toAdminInfos(List<DisplaySectionContent> contents, Map<Long, Product> products) {
        Instant now = clock.instant();
        return contents.stream().map(content -> toAdminInfo(content, products, now)).toList();
    }

    /** @param products 상품 아이템이 가리키는 상품 (숨김 포함) */
    public DisplaySectionAdminInfo toAdminInfo(DisplaySectionContent content, Map<Long, Product> products) {
        return toAdminInfo(content, products, clock.instant());
    }

    // ── 쓰기 판정 ────────────────────────────────────────────────

    /**
     * 요청을 판정용 초안(임시 아이디)으로 바꾸고 저장소 없이 볼 수 있는 규칙을 판정한다 — 섹션 규칙({@link DisplaySectionContent#violation()}) → 미디어 주소.
     * 잠금을 잡기 전에 불러 잠금 시간을 줄인다. 상품 존재는 {@link #validateProductsExist} 가 따로 본다.
     *
     * <p>미디어 주소 — 공개 주소가 설정되지 않았으면 미디어가 하나라도 있는 요청은 {@code PRODUCT_055}(503), 공개 주소 아래가 아니면 {@code PRODUCT_054}(400)다.
     */
    public DisplaySectionContent draftOf(DisplaySectionUpsertCommand command) {
        DisplaySection section = new DisplaySection(
            DRAFT_ID, command.sectionType(), command.title(), command.subtitle(), command.body(),
            command.linkUrl(), command.linkLabel(), 0, command.visible(), command.startAt(), command.endAt()
        );
        List<DisplayItem> items = new ArrayList<>(command.items().size());
        for (int index = 0; index < command.items().size(); index++) {
            items.add(toItem(index, command.items().get(index)));
        }
        DisplaySectionContent draft = new DisplaySectionContent(section, items);

        draft.violation().ifPresent(violation -> {
            throw toException(command.sectionType(), violation);
        });
        validateMediaUrls(draft.mediaUrls());
        return draft;
    }

    /** 상한에 닿았으면 {@code PRODUCT_056}(409)이다. {@code locked} 는 {@link #lockAll()} 결과다. */
    public void validateCanCreate(List<DisplaySection> locked) {
        if (locked.size() >= MAX_SECTION_COUNT) {
            throw new DisplayException(DisplayErrorCode.DISPLAY_SECTION_LIMIT_EXCEEDED, MAX_SECTION_COUNT);
        }
    }

    /** 잠근 섹션 중 대상. 없으면 {@code PRODUCT_046}(404)이다. */
    public DisplaySection requireSection(List<DisplaySection> locked, long sectionId) {
        return locked.stream()
            .filter(section -> section.id() == sectionId)
            .findFirst()
            .orElseThrow(() -> new DisplayException(DisplayErrorCode.NOT_FOUND_DISPLAY_SECTION));
    }

    /**
     * 상품 아이템의 상품이 모두 실제 상품인가(숨김 포함). 없는 아이디가 있으면 {@code PRODUCT_050}(400)이고 메시지에 그 아이디를 모두 싣는다.
     * 숨긴 상품은 등록할 수 있다 — 고객 메인에서만 빠진다.
     *
     * @param found 숨김 포함 상품 조회 결과
     */
    public void validateProductsExist(Set<Long> productIds, Map<Long, Product> found) {
        List<String> missing = productIds.stream().filter(productId -> !found.containsKey(productId)).map(String::valueOf).toList();
        if (!missing.isEmpty()) {
            throw new DisplayException(DisplayErrorCode.DISPLAY_PRODUCT_NOT_FOUND, String.join(", ", missing));
        }
    }

    // ── 쓰기 (잠금 안에서만) ─────────────────────────────────────

    /**
     * 모든 섹션 행을 잠근 뒤 읽는다. 호출한 Facade 의 트랜잭션이 끝날 때까지 다른 어드민 전시 쓰기가 기다리므로, 이 목록으로 한 상한 · 존재 · 순서 판정이 저장할 때까지 유효하다.
     * {@code MANDATORY} — 트랜잭션 밖에서 부르면 바로 실패한다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<DisplaySection> lockAll() {
        return displaySectionRepositoryPort.findAllForUpdate();
    }

    /** 새 섹션 — 아이디를 발급하고 노출 순서는 맨 뒤(현재 최대 + 1, 없으면 0)다. 판정은 호출한 쪽이 끝냈다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public DisplaySectionContent create(DisplaySectionContent draft, List<DisplaySection> locked) {
        int displayOrder = locked.stream().mapToInt(DisplaySection::displayOrder).max().orElse(-1) + 1;
        DisplaySectionContent created = withIds(snowflakeIdGenerator.generateId(), displayOrder, draft);
        displaySectionRepositoryPort.insert(created);
        return created;
    }

    /** 섹션 칸 · 아이템 전체 교체 — 노출 순서는 그대로 두고 아이템 아이디는 새로 발급한다. 존재 판정은 호출한 쪽이 끝냈다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public DisplaySectionContent replace(DisplaySection current, DisplaySectionContent draft) {
        DisplaySectionContent replaced = withIds(current.id(), current.displayOrder(), draft);
        displaySectionRepositoryPort.replace(replaced);
        return replaced;
    }

    /** 섹션과 아이템을 지운다. 존재 판정은 호출한 쪽이 끝냈다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void delete(long sectionId) {
        displaySectionRepositoryPort.delete(sectionId);
    }

    /**
     * 받은 순서대로 0..n-1 을 매긴다. 본문은 지금 섹션 아이디 집합과 정확히 같아야 한다 — 누락 · 중복 · 모르는 아이디는 {@code PRODUCT_057}(400)이다.
     * 섹션이 하나도 없으면 빈 목록만 받는다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reorder(List<DisplaySection> locked, List<Long> sectionIds) {
        Set<Long> current = locked.stream().map(DisplaySection::id).collect(Collectors.toSet());
        if (sectionIds.size() != current.size() || new HashSet<>(sectionIds).size() != sectionIds.size() || !current.containsAll(sectionIds)) {
            throw new DisplayException(DisplayErrorCode.DISPLAY_SECTION_ORDER_MISMATCH);
        }
        Map<Long, Integer> displayOrders = new LinkedHashMap<>();
        for (int index = 0; index < sectionIds.size(); index++) {
            displayOrders.put(sectionIds.get(index), index);
        }
        displaySectionRepositoryPort.changeDisplayOrders(displayOrders);
    }

    // ── 내부 ────────────────────────────────────────────────────

    private List<DisplaySectionContent> withItems(List<DisplaySection> sections) {
        if (sections.isEmpty()) {
            return List.of();
        }
        Map<Long, List<DisplayItem>> itemsBySection = new HashMap<>();
        for (DisplayItem item : displaySectionRepositoryPort.findItemsBySectionIds(sections.stream().map(DisplaySection::id).toList())) {
            itemsBySection.computeIfAbsent(item.sectionId(), sectionId -> new ArrayList<>()).add(item);
        }
        return sections.stream()
            .map(section -> new DisplaySectionContent(section, itemsBySection.getOrDefault(section.id(), List.of())))
            .toList();
    }

    private void validateMediaUrls(List<String> urls) {
        if (urls.isEmpty()) {
            return;
        }
        if (!mediaUrlPolicy.isConfigured()) {
            throw new DisplayException(DisplayErrorCode.DISPLAY_MEDIA_STORAGE_NOT_CONFIGURED);
        }
        if (!urls.stream().allMatch(mediaUrlPolicy::isAllowed)) {
            throw new DisplayException(DisplayErrorCode.DISPLAY_MEDIA_URL_NOT_ALLOWED);
        }
    }

    private static DisplayException toException(DisplaySectionType sectionType, DisplayViolation violation) {
        return switch (violation.kind()) {
            case PERIOD_INVALID -> new DisplayException(DisplayErrorCode.DISPLAY_PERIOD_INVALID);
            case LINK_URL_INVALID -> new DisplayException(DisplayErrorCode.DISPLAY_LINK_URL_INVALID);
            case LINK_LABEL_WITHOUT_URL -> new DisplayException(DisplayErrorCode.DISPLAY_LINK_LABEL_WITHOUT_URL);
            case ITEM_TYPE_NOT_ALLOWED -> new DisplayException(DisplayErrorCode.DISPLAY_ITEM_TYPE_NOT_ALLOWED, sectionType.name(), violation.itemType().name());
            case ITEM_COUNT_OUT_OF_RANGE -> new DisplayException(
                DisplayErrorCode.DISPLAY_ITEM_COUNT_OUT_OF_RANGE, sectionType.name(), violation.itemType().name(),
                sectionType.minCountOf(violation.itemType()), sectionType.maxCountOf(violation.itemType())
            );
            case PRODUCT_DUPLICATED -> new DisplayException(DisplayErrorCode.DISPLAY_PRODUCT_DUPLICATED);
        };
    }

    private DisplaySectionContent withIds(long sectionId, int displayOrder, DisplaySectionContent draft) {
        DisplaySection source = draft.section();
        DisplaySection section = new DisplaySection(
            sectionId, source.sectionType(), source.title(), source.subtitle(), source.body(),
            source.linkUrl(), source.linkLabel(), displayOrder, source.visible(), source.startAt(), source.endAt()
        );
        List<DisplayItem> items = draft.items().stream()
            .map(item -> new DisplayItem(
                snowflakeIdGenerator.generateId(), sectionId, item.displayOrder(), item.itemType(), item.media(), item.productId(), item.caption()
            ))
            .toList();
        return new DisplaySectionContent(section, items);
    }

    /** 판정용 초안 아이템 — 아이디 · 섹션 아이디는 임시 값이다. 받은 순서가 섹션 안의 순서다. */
    private static DisplayItem toItem(int displayOrder, DisplaySectionUpsertCommand.Item item) {
        DisplaySectionUpsertCommand.Media source = item.media();
        DisplayMedia media = source == null ? null : new DisplayMedia(
            source.mediaType(), source.pcUrl(), source.mobileUrl(), source.pcPosterUrl(), source.mobilePosterUrl(), source.altText()
        );
        Long productId = item.itemType() == DisplayItemType.PRODUCT ? item.productId() : null;
        return new DisplayItem(DRAFT_ID, DRAFT_ID, displayOrder, item.itemType(), media, productId, item.caption());
    }

    private static DisplayMainInfo.Product toProduct(ProductSummary summary) {
        ProductImage thumbnail = summary.thumbnail();
        ProductImage hoverImage = summary.hoverImage();
        return new DisplayMainInfo.Product(
            summary.productId(), summary.name(),
            thumbnail == null ? null : new DisplayMainInfo.Thumbnail(thumbnail.id(), thumbnail.url(), thumbnail.width(), thumbnail.height(), thumbnail.altText()),
            hoverImage == null ? null : new DisplayMainInfo.Thumbnail(hoverImage.id(), hoverImage.url(), hoverImage.width(), hoverImage.height(), hoverImage.altText()),
            summary.salePrice(), summary.listPrice(), summary.discountRate(), summary.hasOptionSurcharge(),
            summary.saleStatus(), summary.customOrder(), summary.preorder()
        );
    }

    private static DisplaySectionAdminInfo toAdminInfo(DisplaySectionContent content, Map<Long, Product> products, Instant now) {
        DisplaySection section = content.section();
        List<DisplaySectionAdminInfo.Element> elements = content.items().stream().map(item -> {
            DisplaySectionAdminInfo.ProductReference product = null;
            if (item.isProduct()) {
                Product found = products.get(item.productId());
                product = new DisplaySectionAdminInfo.ProductReference(item.productId(), found == null ? null : found.name(), found != null && found.visible());
            }
            return new DisplaySectionAdminInfo.Element(item.id(), item.itemType(), item.media(), product, item.caption());
        }).toList();
        return new DisplaySectionAdminInfo(
            section.id(), section.sectionType(), section.title(), section.subtitle(), section.body(),
            section.linkUrl(), section.linkLabel(), section.displayOrder(), section.visible(), section.startAt(), section.endAt(),
            section.liveStatusAt(now), elements
        );
    }
}
