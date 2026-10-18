package com.commercefoundry.domainlayer.shippingfee.adapter.in.web.controller;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.web.dto.response.ShippingPolicyResponse;
import com.commercefoundry.domainlayer.shippingfee.application.port.in.ShippingFeeWebUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 고객 배송비 안내. 주문서의 실제 배송비는 order-service 가 internal 견적({@code POST /internal/v1/shipping-fees/quotes})으로 받는다 —
 * 프론트가 이 정책으로 계산한 값은 표시용이다.
 */
@Tag(name = "배송비", description = "스토어프론트 배송비 안내")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/shipping-fees")
public class ShippingFeeWebController {

    private final ShippingFeeWebUseCase shippingFeeWebUseCase;

    @Operation(
        summary = "국내 배송비 정책 조회",
        description = "기본 배송비 · 무료배송 기준 · 도서산간 구간을 낸다. 무료배송은 할인 후 상품 합계가 기준 이상일 때이고, 도서산간 추가 배송비는 무료배송이어도 붙는다. "
            + "정책이 아직 설정되지 않았으면 dataBody 가 null 이다. 로그인 없이 호출할 수 있다."
    )
    @PreAuthorize("permitAll()")
    @GetMapping("/policy")
    public ResponseEntity<Response<ShippingPolicyResponse>> getDomesticPolicy() {
        return ResponseEntity.ok().body(Response.success(shippingFeeWebUseCase.getDomesticPolicy()));
    }
}
