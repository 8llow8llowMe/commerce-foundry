package com.commercefoundry.domainlayer.onboarding.application.service.processor;

import com.commercefoundry.domainlayer.onboarding.application.command.TourProgressUpdateCommand;
import com.commercefoundry.domainlayer.onboarding.application.exception.OnboardingErrorCode;
import com.commercefoundry.domainlayer.onboarding.application.exception.OnboardingException;
import com.commercefoundry.domainlayer.onboarding.application.port.out.TourProgressRepositoryPort;
import com.commercefoundry.domainlayer.onboarding.domain.model.TourProgress;
import com.commercefoundry.domainlayer.onboarding.domain.model.TourStatus;
import com.commercefoundry.global.properties.OnboardingProperties;
import com.commercefoundry.global.properties.OnboardingProperties.Guide;
import com.commercefoundry.persistence.util.SnowflakeIdGenerator;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 운영자별 투어 진행 조회 · 기록 (#357). 트랜잭션은 메서드마다 이 Processor 가 연다 — Facade 는 낙관 충돌이면 새 트랜잭션으로 다시 부르고,
 * 업무 사실 조회(#357 PR-B, 원격 호출)가 같은 Facade 에 섞여도 트랜잭션이 원격 호출을 붙잡지 않게.
 *
 * <p>응답은 언제나 설정 버전 기준으로 본다({@link TourProgress#viewAt}) — 저장 버전이 다르면 미시작이다.
 */
@Component
@RequiredArgsConstructor
public class OnboardingTourProcessor {

    private final TourProgressRepositoryPort tourProgressRepositoryPort;
    private final OnboardingProperties onboardingProperties;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final Clock clock;

    /** 설정 순서대로 투어 전부. 기록이 없거나 버전이 다르면 미시작으로 채운다. 허용 목록에서 빠진 키의 옛 행은 보이지 않는다. */
    @Transactional(readOnly = true)
    public List<TourProgress> getMine(long memberId) {
        Map<String, TourProgress> stored = tourProgressRepositoryPort.findAllByMember(memberId).stream()
            .collect(Collectors.toMap(TourProgress::guideKey, Function.identity()));
        return onboardingProperties.guides().stream()
            .map(guide -> Optional.ofNullable(stored.get(guide.key()))
                .map(progress -> progress.viewAt(guide.version()))
                .orElseGet(() -> TourProgress.notStarted(memberId, guide.key(), guide.version())))
            .toList();
    }

    /**
     * 기록을 합친다. 같은 요청을 다시 보내도 결과가 같다. 미시작 요청은 재실행이다 — 버전과 관계없이 기록을 지우고(없으면 그대로) 미시작을 돌려준다.
     * 안내 기록만 지운다 — 실제 상품 · 설정이나 업무 사실은 건드리지 않는다.
     *
     * <p>요청 버전은 설정 버전과 같아야 한다 — 크면 배포 전({@code 103}), 작으면 옛 화면({@code 020})이다. 같으면 저장 버전에 따라
     * 저장 &lt; 요청은 교체, 저장 = 요청은 병합, 저장 &gt; 설정(설정 롤백)은 그 행을 없는 것으로 보고 요청으로 교체한다 — 롤백 뒤에도 기록이 막히지 않게.
     *
     * @throws OnboardingException {@code GUIDE_NOT_FOUND} · {@code UNRELEASED_GUIDE_VERSION} · {@code STALE_GUIDE_VERSION} ·
     *     {@code CONCURRENT_UPDATE}(저장소가 버전 충돌 · 첫 행 유니크 키 위반을 바꾼 것 — Facade 가 다시 시도한다)
     */
    @Transactional
    public TourProgress update(long memberId, TourProgressUpdateCommand command) {
        Guide guide = onboardingProperties.find(command.guideKey())
            .orElseThrow(() -> new OnboardingException(OnboardingErrorCode.GUIDE_NOT_FOUND));
        if (command.status() == TourStatus.NOT_STARTED) {
            tourProgressRepositoryPort.delete(memberId, guide.key());
            return TourProgress.notStarted(memberId, guide.key(), guide.version());
        }
        if (command.guideVersion() > guide.version()) {
            throw new OnboardingException(OnboardingErrorCode.UNRELEASED_GUIDE_VERSION);
        }
        if (command.guideVersion() < guide.version()) {
            throw new OnboardingException(OnboardingErrorCode.STALE_GUIDE_VERSION);
        }
        Optional<TourProgress> found = tourProgressRepositoryPort.find(memberId, guide.key());
        if (found.isEmpty()) {
            TourProgress started = TourProgress.start(
                snowflakeIdGenerator.generateId(), memberId, guide.key(), command.guideVersion(), command.status(), command.currentStep(), clock.instant()
            );
            return tourProgressRepositoryPort.save(started).viewAt(guide.version());
        }
        TourProgress stored = found.get();
        // 요청 버전 = 설정 버전이 위에서 보장된다. 저장 버전이 더 크면 설정이 롤백된 것 — 응답에서도 미시작으로 보이는 행이라 교체한다
        TourProgress merged = stored.guideVersion() > guide.version()
            ? stored.replace(command.guideVersion(), command.status(), command.currentStep(), clock.instant())
            : stored.merge(command.guideVersion(), command.status(), command.currentStep(), clock.instant());
        if (merged == stored) {
            return stored.viewAt(guide.version());
        }
        return tourProgressRepositoryPort.save(merged).viewAt(guide.version());
    }
}
