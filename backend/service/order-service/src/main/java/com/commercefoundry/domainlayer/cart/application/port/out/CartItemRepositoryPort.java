package com.commercefoundry.domainlayer.cart.application.port.out;

import com.commercefoundry.domainlayer.cart.domain.model.CartItem;
import java.util.List;
import java.util.Optional;

/**
 * 장바구니 줄. 조회는 맞춤 제작 입력까지 채워 돌려준다 — 줄 조회 1번 + 입력 {@code cart_item_id in (...)} 1번이다.
 */
public interface CartItemRepositoryPort {

    /** 회원의 모든 줄. 최근에 담은 순(아이디 내림차순). */
    List<CartItem> findAllByMemberId(long memberId);

    /** 회원의 줄 하나. 없거나 다른 회원의 줄이면 빈 값이다. */
    Optional<CartItem> findByIdAndMemberId(long cartItemId, long memberId);

    /**
     * 새 줄은 맞춤 제작 입력과 함께 넣고, 저장된 줄은 수량만 고친다(입력은 줄의 동일성이라 바뀌지 않는다). 바로 flush 한다.
     *
     * @throws com.commercefoundry.domainlayer.cart.application.exception.CartException {@code CART_CONCURRENTLY_MODIFIED} — 같은 줄을 동시에 만들었거나(유니크 키)
     *     다른 요청이 먼저 고쳤다(@Version)
     */
    void saveAll(List<CartItem> cartItems);

    /** 줄과 그 맞춤 제작 입력을 지운다. */
    void delete(long cartItemId);
}
