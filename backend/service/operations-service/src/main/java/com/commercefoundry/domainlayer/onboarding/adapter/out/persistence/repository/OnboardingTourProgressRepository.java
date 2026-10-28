package com.commercefoundry.domainlayer.onboarding.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.onboarding.adapter.out.persistence.entity.OnboardingTourProgressEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface OnboardingTourProgressRepository extends JpaRepository<OnboardingTourProgressEntity, Long> {

    List<OnboardingTourProgressEntity> findAllByMemberId(long memberId);

    Optional<OnboardingTourProgressEntity> findByMemberIdAndGuideKey(long memberId, String guideKey);

    /**
     * 재실행 — 한 문장으로 지운다. 파생 delete 는 읽은 뒤 버전 비교로 지워, 다른 탭의 갱신과 겹치면 재실행이 충돌로 실패한다.
     * 재실행은 "지금 기록을 버린다" 라 버전과 상관없이 지운다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from OnboardingTourProgressEntity progress where progress.memberId = :memberId and progress.guideKey = :guideKey")
    int deleteByMemberIdAndGuideKey(long memberId, String guideKey);
}
