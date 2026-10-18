package com.commercefoundry.domainlayer.cart.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.cart.adapter.out.persistence.entity.CartItemCustomOptionEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface CartItemCustomOptionRepository extends JpaRepository<CartItemCustomOptionEntity, Long> {

    List<CartItemCustomOptionEntity> findAllByCartItemIdInOrderByCartItemIdAscDisplayOrderAsc(Collection<Long> cartItemIds);

    @Modifying
    @Query("delete from CartItemCustomOptionEntity o where o.cartItemId = :cartItemId")
    int deleteAllOfCartItem(Long cartItemId);
}
