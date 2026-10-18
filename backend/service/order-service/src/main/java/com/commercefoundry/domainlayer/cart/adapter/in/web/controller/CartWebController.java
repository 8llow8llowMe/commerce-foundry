package com.commercefoundry.domainlayer.cart.adapter.in.web.controller;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.cart.adapter.in.web.dto.request.CartItemAddRequest;
import com.commercefoundry.domainlayer.cart.adapter.in.web.dto.request.CartItemQuantityUpdateRequest;
import com.commercefoundry.domainlayer.cart.adapter.in.web.dto.request.CartMergeRequest;
import com.commercefoundry.domainlayer.cart.adapter.in.web.dto.response.CartMergeResponse;
import com.commercefoundry.domainlayer.cart.adapter.in.web.dto.response.CartResponse;
import com.commercefoundry.domainlayer.cart.application.port.in.CartWebUseCase;
import com.commercefoundry.security.principal.MemberPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 회원 장바구니 (로그인 전용, 회원당 하나). 회원은 JWT 주체로만 정한다 — 요청에 memberId 를 받지 않는다 ({@code api-design-guide.md} §5).
 * 비로그인 장바구니는 프론트가 로컬에 두고, 로그인 직후 {@code POST /api/v1/carts/me/merge} 로 한 번 보낸다.
 *
 * <p>본문 검증은 {@code @Valid} 가 한다. 클래스에 {@code @Validated} 를 달지 않는다 ({@code coding-conventions.md} §8-2).
 */
@Tag(name = "장바구니", description = "회원 장바구니 조회 · 담기 · 수량 변경 · 삭제 · 로컬 장바구니 병합")
@SecurityRequirement(name = SwaggerSecurityConfigurer.BEARER_AUTH)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/carts/me")
public class CartWebController {

    private final CartWebUseCase cartWebUseCase;

    @Operation(
        summary = "내 장바구니 조회",
        description = "최근에 담은 순이다. 가격 · 판매 상태는 조회 시점의 현재 값이다 — 담은 뒤 가격이 바뀌거나 품절되면 그대로 반영된다. "
            + "totalAmount 는 구매 가능하고 수량이 상한 안인 줄만 더한다. 상품 서비스 장애면 503(CART_008)이다."
    )
    @PreAuthorize("isAuthenticated()")
    @GetMapping
    public ResponseEntity<Response<CartResponse>> getCart(@AuthenticationPrincipal MemberPrincipal member) {
        return ResponseEntity.ok().body(Response.success(cartWebUseCase.getCart(member.memberId())));
    }

    @Operation(
        summary = "장바구니에 담기",
        description = "같은 SKU · 같은 맞춤 제작 입력이 이미 있으면 수량을 더한다. 없는 SKU 404(CART_002), 품절 · 판매 종료 409(CART_003), "
            + "맞춤 제작이 아닌데 입력이 있으면 400(CART_004), 수량 상한(99 · 1회 주문 최대 수량) 초과 409(CART_005), 50줄 초과 409(CART_006). "
            + "갱신된 장바구니 전체를 돌려준다."
    )
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/items")
    public ResponseEntity<Response<CartResponse>> addItem(
        @AuthenticationPrincipal MemberPrincipal member, @RequestBody @Valid CartItemAddRequest request
    ) {
        return ResponseEntity.ok().body(Response.success(cartWebUseCase.addItem(member.memberId(), request.toCommand())));
    }

    @Operation(
        summary = "장바구니 수량 변경",
        description = "품절 · 판매 종료 줄도 바꿀 수 있다. 없거나 내 줄이 아니면 404(CART_001), 상한 초과 409(CART_005). 갱신된 장바구니 전체를 돌려준다."
    )
    @PreAuthorize("isAuthenticated()")
    @PatchMapping("/items/{itemId}")
    public ResponseEntity<Response<CartResponse>> changeQuantity(
        @AuthenticationPrincipal MemberPrincipal member, @Parameter(description = "장바구니 줄 아이디") @PathVariable long itemId,
        @RequestBody @Valid CartItemQuantityUpdateRequest request
    ) {
        return ResponseEntity.ok().body(Response.success(cartWebUseCase.changeQuantity(member.memberId(), itemId, request.quantity())));
    }

    @Operation(summary = "장바구니에서 삭제", description = "없거나 내 줄이 아니면 404(CART_001). 갱신된 장바구니 전체를 돌려준다.")
    @PreAuthorize("isAuthenticated()")
    @DeleteMapping("/items/{itemId}")
    public ResponseEntity<Response<CartResponse>> removeItem(
        @AuthenticationPrincipal MemberPrincipal member, @Parameter(description = "장바구니 줄 아이디") @PathVariable long itemId
    ) {
        return ResponseEntity.ok().body(Response.success(cartWebUseCase.removeItem(member.memberId(), itemId)));
    }

    @Operation(
        summary = "로컬 장바구니 병합",
        description = "로그인 직후 로컬 장바구니를 한 번 보낸다. 같은 줄은 수량을 더하고 상한에서 자른다(거절하지 않는다). "
            + "없는 SKU · 구매할 수 없는 SKU · 맞춤 제작이 아닌데 입력이 있는 항목 · 50줄을 넘는 새 항목은 항목 단위로 건너뛰고 skippedItems(index · skuId · reason)로 알린다. "
            + "멱등하지 않다 — 성공하면 로컬 장바구니를 비우고, 같은 요청을 다시 보내지 않는다."
    )
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/merge")
    public ResponseEntity<Response<CartMergeResponse>> merge(
        @AuthenticationPrincipal MemberPrincipal member, @RequestBody @Valid CartMergeRequest request
    ) {
        return ResponseEntity.ok().body(Response.success(cartWebUseCase.merge(member.memberId(), request.toCommand())));
    }
}
