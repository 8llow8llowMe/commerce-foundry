package com.commercefoundry.domainlayer.shippingfee.application.service.processor;

import com.commercefoundry.domainlayer.shippingfee.application.command.ShippingPolicyReplaceCommand;
import com.commercefoundry.domainlayer.shippingfee.application.command.ShippingPolicyReplaceCommand.RemoteAreaCommand;
import com.commercefoundry.domainlayer.shippingfee.application.exception.DeliveryErrorCode;
import com.commercefoundry.domainlayer.shippingfee.application.exception.DeliveryException;
import com.commercefoundry.domainlayer.shippingfee.application.port.out.ShippingPolicyHistoryRepositoryPort;
import com.commercefoundry.domainlayer.shippingfee.application.port.out.ShippingPolicyRepositoryPort;
import com.commercefoundry.domainlayer.shippingfee.domain.model.RemoteArea;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingPolicy;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingPolicyHistory;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingPolicyHistory.Snapshot;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import com.commercefoundry.shared.commerce.ShippingScope;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 국내 배송비 정책 조회 · 전체 교체 · 교체 이력. 교체는 입력 검증 → 버전 확인 → 아이디 발급 → 저장 → 이력 append 순서다.
 *
 * <p>어드민 입력의 위반(통화 섞임 · 구간 순서 · 구간 겹침)은 도메인 생성자의 {@link IllegalArgumentException}(500)까지 가지 않게
 * 여기서 먼저 {@link DeliveryException}(400)으로 거절한다.
 */
@Component
@RequiredArgsConstructor
public class ShippingPolicyProcessor {

    private final ShippingPolicyRepositoryPort shippingPolicyRepositoryPort;
    private final ShippingPolicyHistoryRepositoryPort shippingPolicyHistoryRepositoryPort;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    /** @return 정책이 아직 없으면 빈 값 */
    public Optional<ShippingPolicy> findDomesticPolicy() {
        return shippingPolicyRepositoryPort.findDomesticPolicy();
    }

    /**
     * 정책이 없고 {@code version} 이 null 이면 만든다. 정책이 있으면 {@code version} 이 현재 버전과 같을 때만 바꾼다.
     * 도서산간 구간 아이디는 교체마다 새로 발급한다.
     *
     * @throws DeliveryException 003 통화 섞임 · 004 구간 순서 · 005 구간 겹침 · 006 버전 충돌 · 012 저장된 정책의 통화 변경
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
        ShippingPolicyCurrencies.requireUnchanged(current.map(ShippingPolicy::currency), command.baseFee().currency());
        long policyId = current.map(ShippingPolicy::id).orElseGet(snowflakeIdGenerator::generateId);
        ShippingPolicy policy = new ShippingPolicy(
            policyId, ShippingScope.DOMESTIC, command.baseFee(), command.freeShippingThreshold(),
            remoteAreas, command.version(), null
        );

        ShippingPolicy saved;
        try {
            saved = shippingPolicyRepositoryPort.replace(policy);
        } catch (OptimisticLockingFailureException exception) {
            // 위의 버전 확인과 저장 사이에 다른 요청이 먼저 저장했다(동시 PUT · 동시 최초 생성). 어댑터가 조건부 UPDATE 의 0건 ·
            // 범위 유일 키 위반을 이 예외로 알린다. 어댑터가 아니라 여기서 바꾸는 이유 — 같은 예외가 다른 엔티티에서 나도 "배송비 정책 충돌" 로 읽히지 않게
            // 정책 교체 유스케이스 안에서만 006 으로 해석한다
            throw new DeliveryException(DeliveryErrorCode.SHIPPING_POLICY_VERSION_CONFLICT, exception);
        }
        // 정책 저장(버전 검사)이 먼저다 — 동시 교체에서 진 쪽은 이력까지 가지 않는다. 이력 저장이 실패하면 예외가 그대로 올라가
        // Facade 트랜잭션이 정책 교체까지 함께 롤백한다(기록 없는 교체를 남기지 않는다). 값이 그대로인 교체도 남긴다 — 누가 언제 눌렀는지가 감사 대상이다
        shippingPolicyHistoryRepositoryPort.insert(new ShippingPolicyHistory(
            snowflakeIdGenerator.generateId(), saved.id(), ShippingScope.DOMESTIC, command.adminMemberId(),
            current.map(this::toSnapshot).orElse(null), toSnapshot(saved), null
        ));
        return saved;
    }

    /**
     * 국내 정책 교체 이력 — 최신순 한 페이지. 정책이 없거나 이력이 없으면 빈 페이지다.
     * 이어서 auth-service 를 부르므로 Facade 가 아니라 이 조회만 읽기 전용 트랜잭션으로 좁힌다.
     *
     * @param page 0 부터 센다
     */
    @Transactional(readOnly = true)
    public Page<ShippingPolicyHistory> findDomesticHistories(int page, int size) {
        return shippingPolicyHistoryRepositoryPort.findDomesticPage(page, size);
    }

    /** 이력의 구간은 이력 행 아이디를 새로 발급한다 — 정책 구간 아이디는 교체마다 바뀌어 이력에서 뜻이 없고, 같은 구간이 교체 전 · 후 두 행이 된다. */
    private Snapshot toSnapshot(ShippingPolicy policy) {
        List<RemoteArea> remoteAreas = policy.remoteAreas().stream()
            .map(area -> new RemoteArea(snowflakeIdGenerator.generateId(), area.name(), area.postalCodeFrom(), area.postalCodeTo(), area.surcharge()))
            .toList();
        long version = Objects.requireNonNull(policy.version(), "저장된 정책만 이력에 남긴다 — version");
        return new Snapshot(policy.baseFee(), policy.freeShippingThreshold(), remoteAreas, version);
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
