package com.commercefoundry.domainlayer.shippingfee.adapter.out.client.feign.dto;

import java.util.List;

/**
 * auth-service {@code GET /internal/v1/member-display-names} 응답 본문.
 *
 * @param members 요청 순서(중복 제거 후). 없는 아이디는 빠진다
 */
public record MemberDisplayNameInternalResponse(List<MemberDisplayNameInternalItem> members) {
}
