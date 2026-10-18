package com.commercefoundry.domainlayer.cart.support;

import com.commercefoundry.domainlayer.cart.application.exception.CartErrorCode;
import com.commercefoundry.domainlayer.cart.application.exception.CartException;
import com.commercefoundry.domainlayer.cart.application.port.out.CartItemRepositoryPort;
import com.commercefoundry.domainlayer.cart.domain.model.CartItem;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 영속성 포트의 계약을 메모리로 흉내 낸다 — 새 줄은 버전 0 으로 넣고, 저장된 줄은 버전이 같을 때만 고치며 버전을 올린다(@Version 과 같다).
 * 같은 줄(회원 · SKU · 입력 해시)을 두 번 넣으면 유니크 키처럼 거절한다. 실제 질의는 리포지토리 테스트(Testcontainers)가 본다.
 */
public class InMemoryCartItemRepository implements CartItemRepositoryPort {

    private final Map<Long, CartItem> items = new TreeMap<>();
    private final List<List<CartItem>> saveCalls = new ArrayList<>();

    @Override
    public List<CartItem> findAllByMemberId(long memberId) {
        return items.values().stream()
            .filter(item -> item.memberId() == memberId)
            .sorted(Comparator.comparingLong(CartItem::id).reversed())
            .toList();
    }

    @Override
    public Optional<CartItem> findByIdAndMemberId(long cartItemId, long memberId) {
        return Optional.ofNullable(items.get(cartItemId)).filter(item -> item.memberId() == memberId);
    }

    @Override
    public void saveAll(List<CartItem> cartItems) {
        saveCalls.add(List.copyOf(cartItems));
        for (CartItem item : cartItems) {
            if (item.isNew()) {
                boolean duplicate = items.values().stream()
                    .anyMatch(existing -> existing.memberId() == item.memberId() && existing.isSameLine(item.skuId(), item.customOptions()));
                if (duplicate || items.containsKey(item.id())) {
                    throw new CartException(CartErrorCode.CART_CONCURRENTLY_MODIFIED);
                }
                items.put(item.id(), withVersion(item, 0L));
                continue;
            }
            CartItem stored = items.get(item.id());
            if (stored == null || !stored.version().equals(item.version())) {
                throw new CartException(CartErrorCode.CART_CONCURRENTLY_MODIFIED);
            }
            items.put(item.id(), withVersion(item, item.version() + 1));
        }
    }

    @Override
    public void delete(long cartItemId) {
        items.remove(cartItemId);
    }

    /** 테스트 준비 — 이미 저장된 줄로 넣는다. */
    public CartItem givenSaved(CartItem item) {
        CartItem saved = withVersion(item, item.version() == null ? 0L : item.version());
        items.put(saved.id(), saved);
        return saved;
    }

    public List<CartItem> all() {
        return List.copyOf(items.values());
    }

    public Map<Long, Integer> quantitiesBySkuId(long memberId) {
        return findAllByMemberId(memberId).stream().collect(Collectors.toMap(CartItem::skuId, CartItem::quantity, Integer::sum, TreeMap::new));
    }

    /** {@link #saveAll} 호출마다 넘어온 줄 목록. */
    public List<List<CartItem>> saveCalls() {
        return saveCalls;
    }

    private static CartItem withVersion(CartItem item, long version) {
        return new CartItem(item.id(), item.memberId(), item.skuId(), item.quantity(), item.customOptions(), version);
    }
}
