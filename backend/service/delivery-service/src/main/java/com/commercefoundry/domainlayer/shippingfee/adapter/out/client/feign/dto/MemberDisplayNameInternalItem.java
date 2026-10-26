package com.commercefoundry.domainlayer.shippingfee.adapter.out.client.feign.dto;

/**
 * auth-service {@code GET /internal/v1/member-display-names} 항목 중 delivery-service 가 읽는 칸.
 * auth-service 의 DTO 를 가져다 쓰지 않는다. 모르는 칸은 무시한다.
 *
 * @param memberId 회원 아이디 문자열
 * @param displayName 마스킹된 이름. 회원은 있으나 이름이 없으면 null. 원문 이름은 이 계약에 없다
 */
public record MemberDisplayNameInternalItem(String memberId, String displayName) {
}
