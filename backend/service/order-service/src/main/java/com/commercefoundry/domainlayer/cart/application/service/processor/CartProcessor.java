package com.commercefoundry.domainlayer.cart.application.service.processor;

import com.commercefoundry.domainlayer.cart.application.command.CartItemAddCommand;
import com.commercefoundry.domainlayer.cart.application.command.CartMergeCommand;
import com.commercefoundry.domainlayer.cart.application.exception.CartErrorCode;
import com.commercefoundry.domainlayer.cart.application.exception.CartException;
import com.commercefoundry.domainlayer.cart.application.model.CartMergeSkip;
import com.commercefoundry.domainlayer.cart.application.model.CartMergeSkipReason;
import com.commercefoundry.domainlayer.cart.application.model.CartSku;
import com.commercefoundry.domainlayer.cart.application.port.out.CartItemRepositoryPort;
import com.commercefoundry.domainlayer.cart.domain.model.CartItem;
import com.commercefoundry.domainlayer.cart.domain.model.CustomOptions;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 장바구니 줄의 DB 구간. 원격 SKU 조회는 Facade 가 이 트랜잭션 밖에서 먼저 끝내고 결과({@link CartSku})만 넘긴다 —
 * DB 커넥션을 잡은 채 product-service 응답을 기다리지 않게 메서드마다 트랜잭션을 좁힌다 ({@code architecture-guide.md} §3 Facade).
 *
 * <p>소유 확인은 회원 아이디로만 한다 — 다른 회원의 줄은 없는 줄과 같이 {@code CART_ITEM_NOT_FOUND}(404)다.
 */
@Component
@RequiredArgsConstructor
public class CartProcessor {

    private final CartItemRepositoryPort cartItemRepositoryPort;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    /** 최근에 담은 순. */
    @Transactional(readOnly = true)
    public List<CartItem> getItems(long memberId) {
        return cartItemRepositoryPort.findAllByMemberId(memberId);
    }

    @Transactional(readOnly = true)
    public CartItem getItem(long memberId, long cartItemId) {
        return findOwnedItem(memberId, cartItemId);
    }

    /**
     * 담기. 같은 줄(SKU · 맞춤 제작 입력)이 있으면 수량을 더하고, 없으면 새 줄을 만든다.
     *
     * @param sku 담을 수 있는지 이미 확인한 SKU ({@link CartSkuProcessor#getAddableSku})
     * @throws CartException {@code CART_QUANTITY_EXCEEDED} — 합계가 상한을 넘는다. {@code CART_ITEM_LIMIT_EXCEEDED} — 새 줄인데 이미 50줄이다
     */
    @Transactional
    public void addItem(long memberId, CartItemAddCommand command, CartSku sku) {
        int maxQuantity = sku.maxQuantity();
        List<CartItem> items = cartItemRepositoryPort.findAllByMemberId(memberId);
        Optional<CartItem> sameLine = findSameLine(items, command.skuId(), command.customOptions());

        if (sameLine.isPresent()) {
            CartItem item = sameLine.get();
            if (!item.canAdd(command.quantity(), maxQuantity)) {
                throw new CartException(CartErrorCode.CART_QUANTITY_EXCEEDED, maxQuantity);
            }
            cartItemRepositoryPort.saveAll(List.of(item.addQuantity(command.quantity(), maxQuantity)));
            return;
        }
        if (items.size() >= CartItem.MAX_LINES_PER_CART) {
            throw new CartException(CartErrorCode.CART_ITEM_LIMIT_EXCEEDED, CartItem.MAX_LINES_PER_CART);
        }
        if (!CartItem.isQuantityAllowed(command.quantity(), maxQuantity)) {
            throw new CartException(CartErrorCode.CART_QUANTITY_EXCEEDED, maxQuantity);
        }
        cartItemRepositoryPort.saveAll(List.of(newItem(memberId, command.skuId(), command.quantity(), command.customOptions(), maxQuantity)));
    }

    /**
     * 수량 변경. 품절 · 판매 종료 줄도 바꿀 수 있다 — 조회 화면에 상태로 보여줄 뿐 막지 않는다.
     *
     * @param maxQuantity 현재 상한 — SKU 를 찾지 못했으면 {@link CartItem#MAX_QUANTITY}
     */
    @Transactional
    public void changeQuantity(long memberId, long cartItemId, int quantity, int maxQuantity) {
        CartItem item = findOwnedItem(memberId, cartItemId);
        if (!CartItem.isQuantityAllowed(quantity, maxQuantity)) {
            throw new CartException(CartErrorCode.CART_QUANTITY_EXCEEDED, maxQuantity);
        }
        cartItemRepositoryPort.saveAll(List.of(item.changeQuantity(quantity, maxQuantity)));
    }

    @Transactional
    public void removeItem(long memberId, long cartItemId) {
        CartItem item = findOwnedItem(memberId, cartItemId);
        cartItemRepositoryPort.delete(item.id());
    }

    /**
     * 로그인 직후 로컬 장바구니를 합친다. 담지 못하는 항목은 전체를 실패시키지 않고 건너뛴다.
     *
     * <ul>
     *   <li>항목마다 건너뛴다({@link CartMergeSkipReason} 순서로 판정): 없는 SKU · 구매할 수 없는 SKU · 맞춤 제작 상품이 아닌데 맞춤 제작 입력이 있다 ·
     *       새 줄인데 이미 50줄이다 · 새 줄인데 1회 주문 최대 수량이 0 이다. 같은 SKU 라도 항목마다 따로 판정한다</li>
     *   <li>같은 줄이 있으면(병합 요청 안의 같은 줄 포함) 수량을 더하고 상한(99 · 1회 주문 최대 수량)에서 자른다 — 거절하지 않는다.
     *       이미 상한을 넘은 줄은 줄이지 않는다</li>
     *   <li>새 줄은 요청 순서대로 아이디가 커진다 — 나중 항목일수록 최근 담은 줄로 위에 보인다</li>
     *   <li>바뀐 줄은 한 번에 저장한다 — 루프 안에서 하나씩 저장하지 않는다</li>
     * </ul>
     *
     * <p>멱등하지 않다 — 같은 요청을 다시 보내면 수량이 한 번 더 더해진다. 피해는 상한이 제한한다(장바구니라 결제 · 재고와 무관하다).
     *
     * @param skus 병합 요청의 SKU 를 한 번에 조회한 결과 ({@link CartSkuProcessor#findSkus}). 없는 SKU 는 키가 없다
     * @return 건너뛴 항목 — 요청 순서
     */
    @Transactional
    public List<CartMergeSkip> merge(long memberId, CartMergeCommand command, Map<Long, CartSku> skus) {
        List<CartItem> lines = new ArrayList<>(cartItemRepositoryPort.findAllByMemberId(memberId));
        Map<Long, CartItem> changed = new LinkedHashMap<>();
        List<CartMergeSkip> skipped = new ArrayList<>();

        for (int position = 0; position < command.items().size(); position++) {
            CartItemAddCommand item = command.items().get(position);
            CartSku sku = skus.get(item.skuId());
            CartMergeSkipReason rejection = rejectionOf(sku, item);
            if (rejection != null) {
                skipped.add(new CartMergeSkip(position, item.skuId(), rejection));
                continue;
            }
            int maxQuantity = sku.maxQuantity();
            int index = indexOfSameLine(lines, item.skuId(), item.customOptions());
            if (index >= 0) {
                CartItem current = lines.get(index);
                CartItem merged = current.addQuantityUpTo(item.quantity(), maxQuantity);
                if (merged != current) {
                    lines.set(index, merged);
                    changed.put(merged.id(), merged);
                }
                continue;
            }
            if (lines.size() >= CartItem.MAX_LINES_PER_CART) {
                skipped.add(new CartMergeSkip(position, item.skuId(), CartMergeSkipReason.LINE_LIMIT_EXCEEDED));
                continue;
            }
            if (maxQuantity < 1) {
                skipped.add(new CartMergeSkip(position, item.skuId(), CartMergeSkipReason.QUANTITY_LIMIT_EXCEEDED));
                continue;
            }
            CartItem created = newItem(memberId, item.skuId(), Math.min(item.quantity(), maxQuantity), item.customOptions(), maxQuantity);
            lines.add(created);
            changed.put(created.id(), created);
        }

        if (!changed.isEmpty()) {
            cartItemRepositoryPort.saveAll(List.copyOf(changed.values()));
        }
        return List.copyOf(skipped);
    }

    /** SKU 만으로 정해지는 건너뜀 사유. 담을 수 있으면 null. */
    private static CartMergeSkipReason rejectionOf(CartSku sku, CartItemAddCommand item) {
        if (sku == null) {
            return CartMergeSkipReason.SKU_NOT_FOUND;
        }
        if (!sku.purchasable()) {
            return CartMergeSkipReason.NOT_PURCHASABLE;
        }
        if (!sku.accepts(!item.customOptions().isEmpty())) {
            return CartMergeSkipReason.CUSTOM_OPTION_NOT_ALLOWED;
        }
        return null;
    }

    private CartItem findOwnedItem(long memberId, long cartItemId) {
        return cartItemRepositoryPort.findByIdAndMemberId(cartItemId, memberId)
            .orElseThrow(() -> new CartException(CartErrorCode.CART_ITEM_NOT_FOUND));
    }

    private CartItem newItem(long memberId, long skuId, int quantity, CustomOptions customOptions, int maxQuantity) {
        return CartItem.create(
            snowflakeIdGenerator.generateId(), memberId, skuId, quantity, customOptions.withIds(snowflakeIdGenerator::generateId), maxQuantity);
    }

    private static Optional<CartItem> findSameLine(List<CartItem> items, long skuId, CustomOptions customOptions) {
        return items.stream().filter(item -> item.isSameLine(skuId, customOptions)).findFirst();
    }

    private static int indexOfSameLine(List<CartItem> items, long skuId, CustomOptions customOptions) {
        for (int index = 0; index < items.size(); index++) {
            if (items.get(index).isSameLine(skuId, customOptions)) {
                return index;
            }
        }
        return -1;
    }
}
