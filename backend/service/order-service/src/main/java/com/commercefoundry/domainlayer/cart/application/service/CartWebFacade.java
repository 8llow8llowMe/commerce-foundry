package com.commercefoundry.domainlayer.cart.application.service;

import com.commercefoundry.domainlayer.cart.adapter.in.web.dto.response.CartMergeResponse;
import com.commercefoundry.domainlayer.cart.adapter.in.web.dto.response.CartResponse;
import com.commercefoundry.domainlayer.cart.adapter.in.web.presenter.CartWebPresenter;
import com.commercefoundry.domainlayer.cart.application.command.CartItemAddCommand;
import com.commercefoundry.domainlayer.cart.application.command.CartMergeCommand;
import com.commercefoundry.domainlayer.cart.application.info.CartInfo;
import com.commercefoundry.domainlayer.cart.application.info.CartMergeInfo;
import com.commercefoundry.domainlayer.cart.application.model.CartMergeSkip;
import com.commercefoundry.domainlayer.cart.application.model.CartSku;
import com.commercefoundry.domainlayer.cart.application.port.in.CartWebUseCase;
import com.commercefoundry.domainlayer.cart.application.service.processor.CartProcessor;
import com.commercefoundry.domainlayer.cart.application.service.processor.CartSkuProcessor;
import com.commercefoundry.domainlayer.cart.application.service.processor.CartViewProcessor;
import com.commercefoundry.domainlayer.cart.domain.model.CartItem;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 고객 장바구니. <b>트랜잭션을 걸지 않는다</b> — 모든 유스케이스에 product-service 호출(Feign)이 섞여 있어, Facade 에 걸면 DB 커넥션을 잡은 채
 * 원격 응답을 기다린다. SKU 조회는 트랜잭션 밖에서 먼저 하고, DB 구간만 {@link CartProcessor} 의 메서드 단위 트랜잭션으로 좁힌다
 * ({@code architecture-guide.md} §3 Facade).
 *
 * <p>쓰기 뒤 돌려주는 장바구니는 쓰기 트랜잭션이 커밋된 뒤 다시 읽는다 — 쓰기는 커밋됐는데 그 뒤 SKU 조회가 실패하면 503 이다(쓰기는 남는다).
 */
@Service
@RequiredArgsConstructor
public class CartWebFacade implements CartWebUseCase {

    private final CartProcessor cartProcessor;
    private final CartSkuProcessor cartSkuProcessor;
    private final CartViewProcessor cartViewProcessor;
    private final CartWebPresenter cartWebPresenter;

    /** 줄 조회(DB) → 줄들의 SKU 를 한 번에 조회(원격, 줄이 없으면 호출하지 않는다) → 현재 가격 · 상태로 묶는다. */
    @Override
    public CartResponse getCart(long memberId) {
        return cartWebPresenter.toResponse(loadCart(memberId));
    }

    /** SKU 를 먼저 확인해(원격) 담을 수 없으면 DB 에 닿지 않고 거절한다. */
    @Override
    public CartResponse addItem(long memberId, CartItemAddCommand command) {
        CartSku sku = cartSkuProcessor.getAddableSku(command.skuId(), !command.customOptions().isEmpty());
        cartProcessor.addItem(memberId, command, sku);
        return getCart(memberId);
    }

    /**
     * 트랜잭션이 두 구간이다 — ① 줄 소유 확인(읽기, 없거나 남의 줄이면 404) → 원격으로 그 줄의 SKU 상한 조회 → ② 줄을 다시 읽어 수량을 바꾼다.
     *
     * <ul>
     *   <li>①과 ② 사이에 다른 요청이 같은 줄을 고치면 ②가 최신 버전을 다시 읽어 덮어쓴다 — 마지막 쓰기가 이긴다. 수량 지정이라 결과가 요청과 같다.</li>
     *   <li>{@code CART_CONCURRENTLY_MODIFIED}(409)는 ② 구간 안에서 읽은 뒤 쓰기 전에 다른 요청이 커밋했을 때만 난다(@Version).</li>
     *   <li>①과 ② 사이에 줄이 지워지면 ②에서 404 다.</li>
     * </ul>
     */
    @Override
    public CartResponse changeQuantity(long memberId, long cartItemId, int quantity) {
        CartItem item = cartProcessor.getItem(memberId, cartItemId);
        int maxQuantity = cartSkuProcessor.maxQuantityOf(item.skuId());
        cartProcessor.changeQuantity(memberId, cartItemId, quantity, maxQuantity);
        return getCart(memberId);
    }

    /**
     * 삭제는 {@link CartProcessor#removeItem} 한 트랜잭션이다. Facade 에 트랜잭션을 걸지 않는 이유는 삭제 뒤 돌려줄 장바구니를 만들려고 product-service 를
     * 부르기 때문이다 — 걸면 삭제 커밋이 원격 응답을 기다리고, 원격이 실패하면 이미 끝난 삭제까지 되돌린다.
     */
    @Override
    public CartResponse removeItem(long memberId, long cartItemId) {
        cartProcessor.removeItem(memberId, cartItemId);
        return getCart(memberId);
    }

    /** 병합 요청의 SKU 를 한 번에 조회(원격)한 뒤 DB 구간에서 합친다. */
    @Override
    public CartMergeResponse merge(long memberId, CartMergeCommand command) {
        Set<Long> skuIds = new LinkedHashSet<>();
        command.items().forEach(item -> skuIds.add(item.skuId()));
        Map<Long, CartSku> skus = cartSkuProcessor.findSkus(skuIds);
        List<CartMergeSkip> skippedItems = cartProcessor.merge(memberId, command, skus);
        return cartWebPresenter.toMergeResponse(new CartMergeInfo(loadCart(memberId), skippedItems));
    }

    private CartInfo loadCart(long memberId) {
        List<CartItem> items = cartProcessor.getItems(memberId);
        Set<Long> skuIds = new LinkedHashSet<>();
        items.forEach(item -> skuIds.add(item.skuId()));
        return cartViewProcessor.compose(items, cartSkuProcessor.findSkus(skuIds));
    }
}
