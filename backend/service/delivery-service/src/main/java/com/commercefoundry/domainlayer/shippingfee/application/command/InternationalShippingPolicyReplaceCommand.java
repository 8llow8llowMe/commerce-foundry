package com.commercefoundry.domainlayer.shippingfee.application.command;

import com.commercefoundry.shared.commerce.Money;
import java.util.List;
import java.util.Objects;

/**
 * 해외 배송비 정책 전체 교체. 형식 검증(필수 · 길이 · 개수 상한 · 국가 코드 모양)은 요청 DTO 가 끝냈다.
 * 통화 일치 · 국내(KR) 포함 · 그룹 이름 중복 · 국가 중복 · 구간 상한 중복은 Processor 가 도메인 오류 코드로 본다.
 *
 * @param freeShippingThreshold null 이면 무료배송 없음
 * @param customsNotice 관세 · 부가세 안내 문구. 없으면 null (빈 문자열은 요청 DTO 가 null 로 바꿨다)
 * @param zones 국가 그룹 — 이 순서가 표시 순서다
 * @param version 호출자가 조회한 정책 버전. 정책이 아직 없으면 null — 있는데 null 이거나 다르면 버전 충돌이다
 */
public record InternationalShippingPolicyReplaceCommand(
    Money baseFee, Money freeShippingThreshold, String customsNotice, List<ZoneCommand> zones, Long version
) {

    public InternationalShippingPolicyReplaceCommand {
        Objects.requireNonNull(baseFee, "baseFee");
        zones = List.copyOf(zones);
    }

    /** @param countryCodes ISO 3166-1 alpha-2 대문자 */
    public record ZoneCommand(String name, List<String> countryCodes, List<RateCommand> rates) {

        public ZoneCommand {
            Objects.requireNonNull(name, "name");
            countryCodes = List.copyOf(countryCodes);
            rates = List.copyOf(rates);
        }
    }

    /** @param maxWeightGrams 상한 무게 (그램, 포함) */
    public record RateCommand(int maxWeightGrams, Money fee) {

        public RateCommand {
            Objects.requireNonNull(fee, "fee");
        }
    }
}
