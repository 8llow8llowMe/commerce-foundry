package com.commercefoundry.domainlayer.cart.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.cart.adapter.out.persistence.entity.CartItemEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface CartItemRepository extends JpaRepository<CartItemEntity, Long> {

    List<CartItemEntity> findAllByMemberIdOrderByIdDesc(Long memberId);

    Optional<CartItemEntity> findByIdAndMemberId(Long id, Long memberId);

    /** 엔티티를 읽지 않고 한 문장으로 지운다. 소유 확인은 호출한 쪽이 먼저 한다. */
    @Modifying
    @Query("delete from CartItemEntity c where c.id = :id")
    int deleteItem(Long id);
}
