package com.commercefoundry.domainlayer.shippingfee.application.service.processor;

import com.commercefoundry.domainlayer.shippingfee.application.command.InternationalShippingPolicyReplaceCommand;
import com.commercefoundry.domainlayer.shippingfee.application.command.InternationalShippingPolicyReplaceCommand.RateCommand;
import com.commercefoundry.domainlayer.shippingfee.application.command.InternationalShippingPolicyReplaceCommand.ZoneCommand;
import com.commercefoundry.domainlayer.shippingfee.application.exception.DeliveryErrorCode;
import com.commercefoundry.domainlayer.shippingfee.application.exception.DeliveryException;
import com.commercefoundry.domainlayer.shippingfee.application.port.out.InternationalShippingPolicyRepositoryPort;
import com.commercefoundry.domainlayer.shippingfee.domain.model.InternationalShippingPolicy;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingScope;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingZone;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingZoneCountry;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingZoneRate;
import com.commercefoundry.global.properties.StoreProperties;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * 해외 배송비 정책 조회 · 전체 교체. 교체는 입력 검증 → 버전 확인 → 통화 변경 확인 → 아이디 발급 → 저장 순서다 (국내 {@link ShippingPolicyProcessor} 와 같은 틀).
 *
 * <p>어드민 입력의 위반(통화 섞임 · 국내 포함 · 그룹 이름 중복 · 국가 중복 · 구간 상한 중복)은 도메인 생성자의 {@link IllegalArgumentException}(500)까지
 * 가지 않게 여기서 먼저 {@link DeliveryException}(400)으로 거절한다.
 */
@Component
@RequiredArgsConstructor
public class InternationalShippingPolicyProcessor {

    private final InternationalShippingPolicyRepositoryPort internationalShippingPolicyRepositoryPort;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final StoreProperties storeProperties;

    /** 어드민용 — 기능 플래그와 무관하다(켜기 전에 미리 준비할 수 있게). @return 정책이 아직 없으면 빈 값 */
    public Optional<InternationalShippingPolicy> findPolicy() {
        return internationalShippingPolicyRepositoryPort.findPolicy();
    }

    /** 고객 안내용 — 해외 배송이 꺼진 스토어는 정책이 있어도 빈 값이다. 데이터는 그대로 두고 응답에서만 숨긴다 ({@code architecture-guide.md} §9). */
    public Optional<InternationalShippingPolicy> findOfferedPolicy() {
        if (!storeProperties.features().internationalShipping()) {
            return Optional.empty();
        }
        return internationalShippingPolicyRepositoryPort.findPolicy();
    }

    /**
     * 정책이 없고 {@code version} 이 null 이면 만든다. 정책이 있으면 {@code version} 이 현재 버전과 같을 때만 바꾼다.
     * 국가 그룹 · 국가 · 무게 구간 아이디는 교체마다 새로 발급한다.
     *
     * @throws DeliveryException 003 통화 섞임 · 009 국내(KR) 포함 · 010 그룹 이름 중복 · 007 국가 중복 · 008 구간 상한 중복 · 006 버전 충돌 ·
     *     012 저장된 정책의 통화 변경
     */
    public InternationalShippingPolicy replacePolicy(InternationalShippingPolicyReplaceCommand command) {
        validateCurrencies(command);
        validateZones(command.zones());

        try {
            Optional<InternationalShippingPolicy> current = findPolicy();
            requireSameVersion(current, command.version());
            ShippingPolicyCurrencies.requireUnchanged(current.map(InternationalShippingPolicy::currency), command.baseFee().currency());
            long policyId = current.map(InternationalShippingPolicy::id).orElseGet(snowflakeIdGenerator::generateId);
            InternationalShippingPolicy policy = new InternationalShippingPolicy(
                policyId, command.baseFee(), command.freeShippingThreshold(), command.customsNotice(),
                command.zones().stream().map(this::toZone).toList(), command.version(), null
            );
            return internationalShippingPolicyRepositoryPort.replace(policy);
        } catch (OptimisticLockingFailureException exception) {
            // 국내 정책 교체와 같다 — 버전 확인과 저장 사이의 동시 PUT · 동시 최초 생성을 이 유스케이스 안에서만 006 으로 해석한다.
            // 교체 전 조회(findPolicy)도 감싼다 — 쓰기 트랜잭션은 READ COMMITTED 라 동시 교체와 겹치면 하위 행이 엇갈려 읽히고, 어댑터가 그것을 같은 예외로 알린다
            throw new DeliveryException(DeliveryErrorCode.SHIPPING_POLICY_VERSION_CONFLICT, exception);
        }
    }

    private static void validateCurrencies(InternationalShippingPolicyReplaceCommand command) {
        CurrencyCode currency = command.baseFee().currency();
        Stream<Money> rateFees = command.zones().stream().flatMap(zone -> zone.rates().stream()).map(RateCommand::fee);
        boolean mixed = Stream.concat(Stream.ofNullable(command.freeShippingThreshold()), rateFees).anyMatch(money -> money.currency() != currency);
        if (mixed) {
            throw new DeliveryException(DeliveryErrorCode.CURRENCY_MISMATCH);
        }
    }

    /** 그룹 순서대로 보고 처음 만난 위반을 알린다 — 국내 포함 → 그룹 이름 중복 → 국가 중복(그룹 안 · 그룹 간) → 구간 상한 중복. */
    private static void validateZones(List<ZoneCommand> zones) {
        Set<String> zoneNames = new HashSet<>();
        Set<String> countryCodes = new HashSet<>();
        for (ZoneCommand zone : zones) {
            if (zone.countryCodes().contains(ShippingScope.DOMESTIC_COUNTRY_CODE)) {
                throw new DeliveryException(DeliveryErrorCode.SHIPPING_ZONE_DOMESTIC_COUNTRY, zone.name());
            }
            if (!zoneNames.add(zone.name())) {
                throw new DeliveryException(DeliveryErrorCode.SHIPPING_ZONE_NAME_DUPLICATED, zone.name());
            }
            zone.countryCodes().stream().filter(countryCode -> !countryCodes.add(countryCode)).findFirst().ifPresent(countryCode -> {
                throw new DeliveryException(DeliveryErrorCode.SHIPPING_ZONE_COUNTRY_DUPLICATED, countryCode);
            });
            Set<Integer> maxWeights = new HashSet<>();
            zone.rates().stream().map(RateCommand::maxWeightGrams).filter(maxWeight -> !maxWeights.add(maxWeight)).findFirst().ifPresent(maxWeight -> {
                throw new DeliveryException(DeliveryErrorCode.SHIPPING_ZONE_RATE_DUPLICATED, zone.name(), maxWeight);
            });
        }
    }

    /**
     * 정책이 없으면 호출자도 버전을 몰라야 하고(null), 있으면 조회한 버전이 같아야 한다. 어긋나면 아무것도 바꾸지 않는다.
     */
    private static void requireSameVersion(Optional<InternationalShippingPolicy> current, Long requestedVersion) {
        Long currentVersion = current.map(InternationalShippingPolicy::version).orElse(null);
        if (!Objects.equals(currentVersion, requestedVersion)) {
            throw new DeliveryException(DeliveryErrorCode.SHIPPING_POLICY_VERSION_CONFLICT);
        }
    }

    private ShippingZone toZone(ZoneCommand command) {
        List<ShippingZoneCountry> countries = command.countryCodes().stream()
            .map(countryCode -> new ShippingZoneCountry(snowflakeIdGenerator.generateId(), countryCode))
            .toList();
        List<ShippingZoneRate> rates = command.rates().stream()
            .map(rate -> new ShippingZoneRate(snowflakeIdGenerator.generateId(), rate.maxWeightGrams(), rate.fee()))
            .toList();
        return new ShippingZone(snowflakeIdGenerator.generateId(), command.name(), countries, rates);
    }
}
