package com.commercefoundry.domainlayer.payment.adapter.out.client.pg.toss.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 토스 오류 응답 본문 — HTTP 상태 + {@code {code, message}}.
 *
 * @param message 고객에게 보일 수 있는 문구다. 로그에 남기지 않는다
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TossErrorResponse(String code, String message) {

    @Override
    public String toString() {
        return "TossErrorResponse[code=" + code + "]";
    }
}
