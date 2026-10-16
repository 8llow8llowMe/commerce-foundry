package com.commercefoundry.domainlayer.product.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.SkuEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.custom.SkuCustomRepository;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SkuRepository extends JpaRepository<SkuEntity, Long>, SkuCustomRepository {

    List<SkuEntity> findAllByProductIdOrderByIdAsc(Long productId);
}
