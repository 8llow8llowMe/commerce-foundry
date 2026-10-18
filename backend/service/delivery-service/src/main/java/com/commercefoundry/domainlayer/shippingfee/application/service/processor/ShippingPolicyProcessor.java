package com.commercefoundry.domainlayer.shippingfee.application.service.processor;

import com.commercefoundry.domainlayer.shippingfee.application.command.ShippingPolicyReplaceCommand;
import com.commercefoundry.domainlayer.shippingfee.application.command.ShippingPolicyReplaceCommand.RemoteAreaCommand;
import com.commercefoundry.domainlayer.shippingfee.application.exception.DeliveryErrorCode;
import com.commercefoundry.domainlayer.shippingfee.application.exception.DeliveryException;
import com.commercefoundry.domainlayer.shippingfee.application.port.out.ShippingPolicyRepositoryPort;
import com.commercefoundry.domainlayer.shippingfee.domain.model.RemoteArea;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingPolicy;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingScope;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * 국내 배송비 정책 조회 · 전체 교체. 교체는 입력 검증 → 버전 확인 → 아이디 발급 → 저장 순서다.
 *
 * <p>어드민 입력의 위반(통화 섞임 · 구간 순서 · 구간 겹침)은 도메인 생성자의 {@link IllegalArgumentException}(500)까지 가지 않게
 * 여기서 먼저 {@link DeliveryException}(400)으로 거절한다.
 */
@Component
@RequiredArgsConstructor
public class ShippingPolicyProcessor {

    private final ShippingPolicyRepositoryPort shippingPolicyRepositoryPort;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    /** @return 정책이 아직 없으면 빈 값 */
    public Optional<ShippingPolicy> findDomesticPolicy() {
        return shippingPolicyRepositoryPort.findByScope(ShippingScope.DOMESTIC);
    }

    /**
     * 정책이 없고 {@code version} 이 null 이면 만든다. 정책이 있으면 {@code version} 이 현재 버전과 같을 때만 바꾼다.
     * 도서산간 구간 아이디는 교체마다 새로 발급한다.
     *
     * @throws DeliveryException 003 통화 섞임 · 004 구간 순서 · 005 구간 겹침 · 006 버전 충돌
     */
    public ShippingPolicy replaceDomesticPolicy(ShippingPolicyReplaceCommand command) {
        validateCurrencies(command);
        validateRanges(command.remoteAreas());
        List<RemoteArea> remoteAreas = command.remoteAreas().stream().map(this::toRemoteArea).toList();
        ShippingPolicy.findFirstOverlap(remoteAreas).ifPresent(overlap -> {
            throw new DeliveryException(DeliveryErrorCode.REMOTE_AREA_RANGE_OVERLAP, overlap.first().name(), overlap.second().name());
        });

        Optional<ShippingPolicy> current = findDomesticPolicy();
        requireSameVersion(current, command.version());
        long policyId = current.map(ShippingPolicy::id).orElseGet(snowflakeIdGenerator::generateId);
        ShippingPolicy policy = new ShippingPolicy(
            policyId, ShippingScope.DOMESTIC, command.baseFee(), command.freeShippingThreshold(),
            remoteAreas, command.version(), null
        );

        try {
            return shippingPolicyRepositoryPort.replace(policy);
        } catch (OptimisticLockingFailureException exception) {
            // 위의 버전 확인과 저장 사이에 다른 요청이 먼저 저장했다(동시 PUT · 동시 최초 생성). 어댑터가 조건부 UPDATE 의 0건 ·
            // 범위 유일 키 위반을 이 예외로 알린다. 어댑터가 아니라 여기서 바꾸는 이유 — 같은 예외가 다른 엔티티에서 나도 "배송비 정책 충돌" 로 읽히지 않게
            // 정책 교체 유스케이스 안에서만 006 으로 해석한다
            throw new DeliveryException(DeliveryErrorCode.SHIPPING_POLICY_VERSION_CONFLICT, exception);
        }
    }

    private static void validateCurrencies(ShippingPolicyReplaceCommand command) {
        CurrencyCode currency = command.baseFee().currency();
        boolean mixed = (command.freeShippingThreshold() != null && command.freeShippingThreshold().currency() != currency)
            || command.remoteAreas().stream().map(RemoteAreaCommand::surcharge).map(Money::currency).anyMatch(surchargeCurrency -> surchargeCurrency != currency);
        if (mixed) {
            throw new DeliveryException(DeliveryErrorCode.CURRENCY_MISMATCH);
        }
    }

    private static void validateRanges(List<RemoteAreaCommand> remoteAreas) {
        remoteAreas.stream()
            .filter(area -> area.postalCodeFrom().isAfter(area.postalCodeTo()))
            .findFirst()
            .ifPresent(area -> {
                throw new DeliveryException(DeliveryErrorCode.REMOTE_AREA_RANGE_INVALID, area.name());
            });
    }

    /**
     * 정책이 없으면 호출자도 버전을 몰라야 하고(null), 있으면 조회한 버전이 같아야 한다. 어긋나면 아무것도 바꾸지 않는다.
     * 정책이 없는데 버전을 보냈다면 그 사이 정책이 지워진 것이라 마찬가지로 다시 조회해야 한다.
     */
    private static void requireSameVersion(Optional<ShippingPolicy> current, Long requestedVersion) {
        Long currentVersion = current.map(ShippingPolicy::version).orElse(null);
        if (!Objects.equals(currentVersion, requestedVersion)) {
            throw new DeliveryException(DeliveryErrorCode.SHIPPING_POLICY_VERSION_CONFLICT);
        }
    }

    private RemoteArea toRemoteArea(RemoteAreaCommand command) {
        return new RemoteArea(snowflakeIdGenerator.generateId(), command.name(), command.postalCodeFrom(), command.postalCodeTo(), command.surcharge());
    }
}
