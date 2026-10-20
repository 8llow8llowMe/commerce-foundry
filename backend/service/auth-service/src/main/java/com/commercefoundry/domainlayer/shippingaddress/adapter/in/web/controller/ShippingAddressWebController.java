package com.commercefoundry.domainlayer.shippingaddress.adapter.in.web.controller;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.shippingaddress.adapter.in.web.dto.request.ShippingAddressCreateRequest;
import com.commercefoundry.domainlayer.shippingaddress.adapter.in.web.dto.request.ShippingAddressUpdateRequest;
import com.commercefoundry.domainlayer.shippingaddress.adapter.in.web.dto.response.ShippingAddressCreateResponse;
import com.commercefoundry.domainlayer.shippingaddress.adapter.in.web.dto.response.ShippingAddressListResponse;
import com.commercefoundry.domainlayer.shippingaddress.adapter.in.web.dto.response.ShippingAddressResponse;
import com.commercefoundry.domainlayer.shippingaddress.application.port.in.ShippingAddressWebUseCase;
import com.commercefoundry.security.principal.MemberPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내 배송지 주소록. 회원은 access 토큰의 memberId 로만 정한다 — 경로 · 본문으로 회원 아이디를 받지 않는다 ({@code api-design-guide.md} §5).
 * 없거나 남의 주소는 같은 404(AUTH_020)다 — 남의 주소가 있는지 드러내지 않는다.
 */
@Tag(name = "배송지", description = "내 배송지 주소록 조회 · 추가 · 수정 · 삭제 · 기본 배송지 지정")
@SecurityRequirement(name = SwaggerSecurityConfigurer.BEARER_AUTH)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/members/me/shipping-addresses")
public class ShippingAddressWebController {

    private final ShippingAddressWebUseCase shippingAddressWebUseCase;

    @Operation(summary = "내 배송지 목록", description = "기본 배송지가 먼저, 그다음 최신순이다. 페이지 없이 전체를 낸다(회원당 상한 maxCount). 없으면 빈 배열이다.")
    @PreAuthorize("isAuthenticated()")
    @GetMapping
    public ResponseEntity<Response<ShippingAddressListResponse>> getMyAddresses(@Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member) {
        return ResponseEntity.ok().body(Response.success(shippingAddressWebUseCase.getMyAddresses(member.memberId())));
    }

    @Operation(summary = "내 배송지 단건", description = "없거나 다른 회원의 주소면 404(AUTH_020)다.")
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/{addressId}")
    public ResponseEntity<Response<ShippingAddressResponse>> getMyAddress(
        @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member, @Parameter(description = "배송지 아이디") @PathVariable long addressId
    ) {
        return ResponseEntity.ok().body(Response.success(shippingAddressWebUseCase.getMyAddress(member.memberId(), addressId)));
    }

    @Operation(
        summary = "배송지 추가",
        description = "회원의 첫 배송지는 isDefault 와 무관하게 기본이 된다. isDefault=true 면 기존 기본이 풀린다. "
            + "회원당 상한을 넘으면 409(AUTH_021), 입력 규칙 위반은 400(AUTH_120~129)이다."
    )
    @PreAuthorize("isAuthenticated()")
    @PostMapping
    public ResponseEntity<Response<ShippingAddressCreateResponse>> create(
        @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member, @Valid @RequestBody ShippingAddressCreateRequest request
    ) {
        ShippingAddressCreateResponse response = shippingAddressWebUseCase.create(member.memberId(), request.toCommand(), request.requestedDefault());
        return ResponseEntity.status(HttpStatus.CREATED).body(Response.success(response));
    }

    @Operation(
        summary = "배송지 수정 (전체 교체)",
        description = "보내지 않은 선택 칸은 지운다. 기본 배송지 여부는 바꾸지 않는다(isDefault 를 보내도 무시한다). "
            + "없거나 다른 회원의 주소면 404(AUTH_020), 다른 요청이 먼저 고쳤으면 409(AUTH_022) — 다시 조회한 뒤 보낸다."
    )
    @PreAuthorize("isAuthenticated()")
    @PutMapping("/{addressId}")
    public ResponseEntity<Response<ShippingAddressResponse>> update(
        @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member, @Parameter(description = "배송지 아이디") @PathVariable long addressId,
        @Valid @RequestBody ShippingAddressUpdateRequest request
    ) {
        return ResponseEntity.ok().body(Response.success(shippingAddressWebUseCase.update(member.memberId(), addressId, request.toCommand())));
    }

    @Operation(
        summary = "배송지 삭제",
        description = "기본 배송지를 지우고 남은 주소가 있으면 가장 최근에 추가한 주소가 기본이 된다. dataBody 는 null 이다. 없거나 다른 회원의 주소면 404(AUTH_020)다."
    )
    @PreAuthorize("isAuthenticated()")
    @DeleteMapping("/{addressId}")
    public ResponseEntity<Response<Void>> delete(
        @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member, @Parameter(description = "배송지 아이디") @PathVariable long addressId
    ) {
        shippingAddressWebUseCase.delete(member.memberId(), addressId);
        return ResponseEntity.ok().body(Response.success());
    }

    @Operation(
        summary = "기본 배송지 지정",
        description = "기존 기본 배송지는 풀린다. 이미 기본이어도 200 이다(멱등). dataBody 는 null 이다. 없거나 다른 회원의 주소면 404(AUTH_020)다."
    )
    @PreAuthorize("isAuthenticated()")
    @PutMapping("/{addressId}/default")
    public ResponseEntity<Response<Void>> setDefault(
        @Parameter(hidden = true) @AuthenticationPrincipal MemberPrincipal member, @Parameter(description = "배송지 아이디") @PathVariable long addressId
    ) {
        shippingAddressWebUseCase.setDefault(member.memberId(), addressId);
        return ResponseEntity.ok().body(Response.success());
    }
}
