package com.commercefoundry.domainlayer.guestsession.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.guestsession.adapter.out.persistence.entity.GuestSessionEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GuestSessionRepository extends JpaRepository<GuestSessionEntity, Long> {
    Optional<GuestSessionEntity> findByTokenHash(String tokenHash);
    @Query("select session.id from GuestSessionEntity session where session.tokenHash = :tokenHash")
    Optional<Long> findIdByTokenHash(String tokenHash);
}
