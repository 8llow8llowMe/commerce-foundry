package com.commercefoundry.domainlayer.onboarding.adapter.out.persistence;

import com.commercefoundry.domainlayer.onboarding.adapter.out.persistence.entity.OnboardingTourProgressEntity;
import com.commercefoundry.domainlayer.onboarding.adapter.out.persistence.repository.OnboardingTourProgressRepository;
import com.commercefoundry.domainlayer.onboarding.application.exception.OnboardingErrorCode;
import com.commercefoundry.domainlayer.onboarding.application.exception.OnboardingException;
import com.commercefoundry.domainlayer.onboarding.application.port.out.TourProgressRepositoryPort;
import com.commercefoundry.domainlayer.onboarding.domain.model.TourProgress;
import com.commercefoundry.persistence.util.ConstraintViolations;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * 쓰기는 바로 flush 한다 — 버전 충돌 · 첫 행 유니크 키 위반을 이 자리에서 {@code ONBOARDING_021} 로 바꿔, Facade 가 새 트랜잭션에서 다시 읽어 합치게 한다.
 * 커밋 시점까지 미루면 트랜잭션 밖에서 터져 도메인 예외로 바꿀 수 없다.
 */
@Component
@RequiredArgsConstructor
public class OnboardingTourProgressRepositoryAdapter implements TourProgressRepositoryPort {

    static final String MEMBER_GUIDE_UNIQUE_CONSTRAINT = "uk_onboarding_tour_progress_member_id_guide_key";

    private final OnboardingTourProgressRepository repository;

    @Override
    public List<TourProgress> findAllByMember(long memberId) {
        return repository.findAllByMemberId(memberId).stream().map(OnboardingTourProgressEntity::toDomain).toList();
    }

    @Override
    public Optional<TourProgress> find(long memberId, String guideKey) {
        return repository.findByMemberIdAndGuideKey(memberId, guideKey).map(OnboardingTourProgressEntity::toDomain);
    }

    /**
     * 같은 운영자 · 투어의 첫 행을 두 탭이 동시에 만들면 진 쪽은 유니크 키 위반이다 — 제약 이름으로 가려 충돌로 바꾼다. 그 밖의 위반(아이디 충돌 등)은 예상 밖이라 그대로 올린다.
     * 읽은 뒤 다른 탭이 고쳤거나 지웠으면 merge 의 버전 비교가 실패한다.
     */
    @Override
    public TourProgress save(TourProgress progress) {
        try {
            return repository.saveAndFlush(OnboardingTourProgressEntity.from(progress)).toDomain();
        } catch (OptimisticLockingFailureException exception) {
            throw new OnboardingException(OnboardingErrorCode.CONCURRENT_UPDATE, exception);
        } catch (DataIntegrityViolationException exception) {
            if (ConstraintViolations.violates(exception, MEMBER_GUIDE_UNIQUE_CONSTRAINT)) {
                throw new OnboardingException(OnboardingErrorCode.CONCURRENT_UPDATE, exception);
            }
            throw exception;
        }
    }

    @Override
    public int delete(long memberId, String guideKey) {
        return repository.deleteByMemberIdAndGuideKey(memberId, guideKey);
    }
}
