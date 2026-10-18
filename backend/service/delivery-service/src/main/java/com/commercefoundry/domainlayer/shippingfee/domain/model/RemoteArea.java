package com.commercefoundry.domainlayer.shippingfee.domain.model;

import com.commercefoundry.shared.commerce.Money;
import java.util.Objects;

/**
 * 도서산간 우편번호 구간과 추가 배송비. 구간은 양 끝을 포함한다 ({@code postalCodeFrom <= 우편번호 <= postalCodeTo}).
 *
 * @param name 어드민이 알아보는 이름 (예: 제주)
 * @param surcharge 추가 배송비 (0 이상). 통화는 정책 기본 배송비와 같다 — {@link ShippingPolicy} 가 확인한다
 */
public record RemoteArea(long id, String name, PostalCode postalCodeFrom, PostalCode postalCodeTo, Money surcharge) {

    public RemoteArea {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(postalCodeFrom, "postalCodeFrom");
        Objects.requireNonNull(postalCodeTo, "postalCodeTo");
        Objects.requireNonNull(surcharge, "surcharge");
        if (postalCodeFrom.isAfter(postalCodeTo)) {
            throw new IllegalArgumentException("도서산간 구간의 시작이 끝보다 큽니다: %s~%s".formatted(postalCodeFrom, postalCodeTo));
        }
    }

    public boolean contains(PostalCode postalCode) {
        return !postalCodeFrom.isAfter(postalCode) && !postalCode.isAfter(postalCodeTo);
    }

    /** 한 우편번호라도 같이 포함하면 겹친다. 맞닿은 구간({@code ~63099} 과 {@code 63100~})은 겹치지 않는다. */
    public boolean overlaps(RemoteArea other) {
        return !postalCodeFrom.isAfter(other.postalCodeTo) && !other.postalCodeFrom.isAfter(postalCodeTo);
    }
}
