package com.commercefoundry.domainlayer.look.application.service.processor;

import com.commercefoundry.domainlayer.look.application.command.LookUpsertCommand;
import com.commercefoundry.domainlayer.look.application.exception.LookErrorCode;
import com.commercefoundry.domainlayer.look.application.exception.LookException;
import com.commercefoundry.domainlayer.look.application.info.LookAdminInfo;
import com.commercefoundry.domainlayer.look.application.info.LookProductAdminInfo;
import com.commercefoundry.domainlayer.look.application.port.out.LookRepositoryPort;
import com.commercefoundry.domainlayer.look.domain.model.Look;
import com.commercefoundry.domainlayer.look.domain.model.LookContent;
import com.commercefoundry.domainlayer.look.domain.model.LookImage;
import com.commercefoundry.domainlayer.look.domain.model.LookItem;
import com.commercefoundry.domainlayer.look.domain.model.LookLimits;
import com.commercefoundry.domainlayer.product.domain.model.Product;
import com.commercefoundry.global.media.MediaUrlPolicy;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
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
 * 룩 읽기 · 쓰기 · 판정. 룩 수에 상한({@value LookLimits#MAX_LOOK_COUNT})이 있어 어드민은 전체를 한 번에 읽고, 아이템은 룩 아이디 in 절로 한 번에 읽는다 (N+1 금지).
 *
 * <p>상품 정보는 이 컨텍스트가 읽지 않는다 — Facade 가 상품 컨텍스트 Processor 로 받은 domain model({@link Product})을 인자로 넘긴다
 * ({@code architecture-guide.md} §3 컨텍스트 간 의존 규칙, 의존 방향 look → product).
 *
 * <p>쓰기 메서드는 판정을 다시 하지 않는다. Facade 가 {@link #lockAll()} 로 잠근 룩을 받아 판정 메서드를 부른 뒤 부른다 — 잠금 · 쓰기 메서드는 {@code MANDATORY} 다.
 * 구조는 메인 전시({@code DisplaySectionProcessor})와 같다.
 */
@Component
@RequiredArgsConstructor
public class LookProcessor {

    /** 판정용 초안의 임시 아이디 — 저장할 때 Snowflake 로 바꾼다. */
    private static final long DRAFT_ID = 0L;

    private final LookRepositoryPort lookRepositoryPort;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final MediaUrlPolicy mediaUrlPolicy;

    // ── 읽기 ────────────────────────────────────────────────────

    /** 어드민 목록 — 숨긴 룩 포함 전체 룩과 아이템. 룩 1번 · 아이템 1번 읽는다. */
    public List<LookContent> getAllContents() {
        return withItems(lookRepositoryPort.findAll());
    }

    /**
     * 없으면 {@code LOOK_001}(404)이다. 읽기는 잠그지 않아 룩 행을 읽은 뒤 아이템을 읽기 전에 다른 어드민의 삭제가 커밋될 수 있다(READ COMMITTED 의 문장별 스냅숏) —
     * 그때 아이템이 비면 지워진 룩이므로 같은 404 로 답한다(구성 상품 1개 이상 불변식으로 500 이 나지 않게).
     */
    public LookContent getContent(long lookId) {
        Look look = lookRepositoryPort.findById(lookId).orElseThrow(() -> new LookException(LookErrorCode.NOT_FOUND_LOOK));
        List<LookItem> items = lookRepositoryPort.findItemsByLookIds(List.of(lookId));
        if (items.isEmpty()) {
            throw new LookException(LookErrorCode.NOT_FOUND_LOOK);
        }
        return new LookContent(look, items);
    }

    /** 룩들의 구성 상품 아이디 — 모든 룩을 합쳐 중복 없이. Facade 가 상품 조회를 한 번에 하게 한다. */
    public Set<Long> productIdsOf(Collection<LookContent> contents) {
        Set<Long> productIds = new LinkedHashSet<>();
        contents.forEach(content -> productIds.addAll(content.productIds()));
        return productIds;
    }

    /** @param products 구성 상품 (숨김 · 판매 종료 포함) */
    public List<LookAdminInfo> toAdminInfos(List<LookContent> contents, Map<Long, Product> products) {
        return contents.stream().map(content -> toAdminInfo(content, products)).toList();
    }

    /** @param products 구성 상품 (숨김 · 판매 종료 포함). 없는 상품(데이터 오류)은 이름 · 판매 상태가 null 이다 */
    public LookAdminInfo toAdminInfo(LookContent content, Map<Long, Product> products) {
        Look look = content.look();
        List<LookProductAdminInfo> items = content.items().stream().map(item -> {
            Product found = products.get(item.productId());
            return found == null
                ? new LookProductAdminInfo(item.productId(), null, false, null)
                : new LookProductAdminInfo(item.productId(), found.name(), found.visible(), found.saleStatus());
        }).toList();
        return new LookAdminInfo(look.id(), look.title(), look.description(), look.image(), look.visible(), items);
    }

    // ── 쓰기 판정 ────────────────────────────────────────────────

    /**
     * 요청을 판정용 초안(임시 아이디)으로 바꾸고 저장소 없이 볼 수 있는 규칙을 판정한다 — 상품 중복({@code LOOK_003}) → 이미지 주소.
     * 잠금을 잡기 전에 불러 잠금 시간을 줄인다. 상품 존재는 {@link #validateProductsExist} 가 따로 본다.
     *
     * <p>이미지 주소 — 이미지는 필수라 공개 주소가 설정되지 않았으면 모든 쓰기가 {@code LOOK_006}(503), 공개 주소 아래가 아니면 {@code LOOK_005}(400)다.
     */
    public LookContent draftOf(LookUpsertCommand command) {
        if (LookContent.hasDuplicateProduct(command.productIds())) {
            throw new LookException(LookErrorCode.LOOK_PRODUCT_DUPLICATED);
        }
        LookUpsertCommand.Image source = command.image();
        validateImageUrl(source.url());

        Look look = new Look(
            DRAFT_ID, command.title(), command.description(), new LookImage(source.url(), source.width(), source.height(), source.altText()), command.visible(), 0
        );
        List<LookItem> items = new ArrayList<>(command.productIds().size());
        for (int index = 0; index < command.productIds().size(); index++) {
            items.add(new LookItem(DRAFT_ID, DRAFT_ID, command.productIds().get(index), index));
        }
        return new LookContent(look, items);
    }

    /** 상한에 닿았으면 {@code LOOK_002}(409)다. {@code locked} 는 {@link #lockAll()} 결과다. */
    public void validateCanCreate(List<Look> locked) {
        if (locked.size() >= LookLimits.MAX_LOOK_COUNT) {
            throw new LookException(LookErrorCode.LOOK_LIMIT_EXCEEDED, LookLimits.MAX_LOOK_COUNT);
        }
    }

    /** 잠근 룩 중 대상. 없으면 {@code LOOK_001}(404)이다. */
    public Look requireLook(List<Look> locked, long lookId) {
        return locked.stream()
            .filter(look -> look.id() == lookId)
            .findFirst()
            .orElseThrow(() -> new LookException(LookErrorCode.NOT_FOUND_LOOK));
    }

    /**
     * 구성 상품이 모두 실제 상품인가(숨김 · 판매 종료 포함). 없는 아이디가 있으면 {@code LOOK_004}(400)이고 메시지에 그 아이디를 모두 싣는다.
     * 숨김 · 판매 종료 상품은 등록할 수 있다 — 고객 화면에서 어떻게 보일지는 고객 조회가 정한다.
     *
     * @param found 숨김 포함 상품 조회 결과
     */
    public void validateProductsExist(Collection<Long> productIds, Map<Long, Product> found) {
        List<String> missing = productIds.stream().filter(productId -> !found.containsKey(productId)).map(String::valueOf).toList();
        if (!missing.isEmpty()) {
            throw new LookException(LookErrorCode.LOOK_PRODUCT_NOT_FOUND, String.join(", ", missing));
        }
    }

    // ── 쓰기 (잠금 안에서만) ─────────────────────────────────────

    /**
     * 모든 룩 행을 잠근 뒤 읽는다. 호출한 Facade 의 트랜잭션이 끝날 때까지 다른 어드민 룩 쓰기가 기다리므로, 이 목록으로 한 상한 · 존재 · 순서 판정이 저장할 때까지 유효하다.
     * {@code MANDATORY} — 트랜잭션 밖에서 부르면 바로 실패한다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Look> lockAll() {
        return lookRepositoryPort.findAllForUpdate();
    }

    /** 새 룩 — 아이디를 발급하고 노출 순서는 맨 뒤(현재 최대 + 1, 없으면 0)다. 판정은 호출한 쪽이 끝냈다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public LookContent create(LookContent draft, List<Look> locked) {
        int displayOrder = locked.stream().mapToInt(Look::displayOrder).max().orElse(-1) + 1;
        LookContent created = withIds(snowflakeIdGenerator.generateId(), displayOrder, draft);
        lookRepositoryPort.insert(created);
        return created;
    }

    /** 룩 칸 · 구성 상품 전체 교체 — 노출 순서는 그대로 두고 아이템 아이디는 새로 발급한다. 존재 판정은 호출한 쪽이 끝냈다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public LookContent replace(Look current, LookContent draft) {
        LookContent replaced = withIds(current.id(), current.displayOrder(), draft);
        lookRepositoryPort.replace(replaced);
        return replaced;
    }

    /** 룩과 아이템을 지운다. 존재 판정은 호출한 쪽이 끝냈다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void delete(long lookId) {
        lookRepositoryPort.delete(lookId);
    }

    /**
     * 받은 순서대로 0..n-1 을 매긴다. 본문은 지금 룩 아이디 집합과 정확히 같아야 한다 — 누락 · 중복 · 모르는 아이디는 {@code LOOK_007}(400)이다.
     * 룩이 하나도 없으면 빈 목록만 받는다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reorder(List<Look> locked, List<Long> lookIds) {
        Set<Long> current = locked.stream().map(Look::id).collect(Collectors.toSet());
        if (lookIds.size() != current.size() || new HashSet<>(lookIds).size() != lookIds.size() || !current.containsAll(lookIds)) {
            throw new LookException(LookErrorCode.LOOK_ORDER_MISMATCH);
        }
        Map<Long, Integer> displayOrders = new LinkedHashMap<>();
        for (int index = 0; index < lookIds.size(); index++) {
            displayOrders.put(lookIds.get(index), index);
        }
        lookRepositoryPort.changeDisplayOrders(displayOrders);
    }

    // ── 내부 ────────────────────────────────────────────────────

    /**
     * 룩마다 아이템을 붙인다. 아이템이 없는 룩은 결과에서 뺀다 — 룩 행을 읽은 뒤 아이템을 읽기 전에 다른 어드민의 삭제가 커밋된 경우다
     * (잠그지 않는 읽기, 문장별 스냅숏). 구성 상품 1개 이상 불변식으로 500 이 나지 않게 하고, 지워진 룩은 목록에 없는 것이 맞다.
     */
    private List<LookContent> withItems(List<Look> looks) {
        if (looks.isEmpty()) {
            return List.of();
        }
        Map<Long, List<LookItem>> itemsByLook = new HashMap<>();
        for (LookItem item : lookRepositoryPort.findItemsByLookIds(looks.stream().map(Look::id).toList())) {
            itemsByLook.computeIfAbsent(item.lookId(), lookId -> new ArrayList<>()).add(item);
        }
        return looks.stream()
            .filter(look -> itemsByLook.containsKey(look.id()))
            .map(look -> new LookContent(look, itemsByLook.get(look.id())))
            .toList();
    }

    private void validateImageUrl(String url) {
        if (!mediaUrlPolicy.isConfigured()) {
            throw new LookException(LookErrorCode.LOOK_MEDIA_STORAGE_NOT_CONFIGURED);
        }
        if (!mediaUrlPolicy.isAllowed(url)) {
            throw new LookException(LookErrorCode.LOOK_IMAGE_URL_NOT_ALLOWED);
        }
    }

    private LookContent withIds(long lookId, int displayOrder, LookContent draft) {
        Look source = draft.look();
        Look look = new Look(lookId, source.title(), source.description(), source.image(), source.visible(), displayOrder);
        List<LookItem> items = draft.items().stream()
            .map(item -> new LookItem(snowflakeIdGenerator.generateId(), lookId, item.productId(), item.displayOrder()))
            .toList();
        return new LookContent(look, items);
    }
}
