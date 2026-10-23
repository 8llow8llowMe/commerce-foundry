package com.commercefoundry.domainlayer.shippingfee.application.command;

import com.commercefoundry.domainlayer.shippingfee.domain.model.PostalCode;
import com.commercefoundry.shared.commerce.Money;
import java.util.List;
import java.util.Objects;

/**
 * 배송비 정책 전체 교체. 형식 검증(필수 · 길이 · 우편번호 모양)은 요청 DTO 가 끝냈다. 통화 일치 · 구간 순서 · 겹침은 Processor 가 본다.
 *
 * @param freeShippingThreshold null 이면 무료배송 없음
 * @param version 호출자가 조회한 정책 버전. 정책이 아직 없으면 null — 있는데 null 이거나 다르면 버전 충돌이다
 * @param adminMemberId 교체하는 어드민 회원 아이디 — access 토큰의 memberId 다(요청 본문으로 받지 않는다). 교체 이력에 남는다 (#69)
 */
public record ShippingPolicyReplaceCommand(Money baseFee, Money freeShippingThreshold, List<RemoteAreaCommand> remoteAreas, Long version, long adminMemberId) {

    public ShippingPolicyReplaceCommand {
        Objects.requireNonNull(baseFee, "baseFee");
        remoteAreas = List.copyOf(remoteAreas);
    }

    public record RemoteAreaCommand(String name, PostalCode postalCodeFrom, PostalCode postalCodeTo, Money surcharge) {

        public RemoteAreaCommand {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(postalCodeFrom, "postalCodeFrom");
            Objects.requireNonNull(postalCodeTo, "postalCodeTo");
            Objects.requireNonNull(surcharge, "surcharge");
        }
    }
}
