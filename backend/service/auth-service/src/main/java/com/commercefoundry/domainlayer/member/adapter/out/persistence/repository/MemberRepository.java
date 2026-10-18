package com.commercefoundry.domainlayer.member.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.member.adapter.out.persistence.entity.MemberEntity;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface MemberRepository extends JpaRepository<MemberEntity, Long> {

    boolean existsByEmail(String email);

    /**
     * 읽은 버전일 때만 버전을 하나 올린다 — 조건부 UPDATE 한 문장. 바뀐 행 수가 0 이면 다른 요청이 먼저 고쳤다.
     * 벌크 UPDATE 라 감사 리스너가 돌지 않아 수정 시각을 직접 넣고, 영속성 컨텍스트의 옛 버전을 쓰지 않게 비운다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update MemberEntity m set m.version = m.version + 1, m.updatedAt = :now where m.id = :id and m.version = :version")
    int incrementVersion(Long id, Long version, Instant now);
}
