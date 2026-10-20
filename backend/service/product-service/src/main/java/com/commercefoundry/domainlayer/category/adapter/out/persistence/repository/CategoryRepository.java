package com.commercefoundry.domainlayer.category.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.category.adapter.out.persistence.entity.CategoryEntity;
import jakarta.persistence.LockModeType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface CategoryRepository extends JpaRepository<CategoryEntity, Long> {

    /**
     * 어드민 쓰기 직렬화용 — 모든 카테고리 행에 쓰기 잠금을 건다. 아이디 오름차순으로 잠가 두 쓰기가 서로 다른 순서로 잠그며 교착하지 않게 한다.
     * 카테고리는 수가 적어 표 전체를 잠가도 비용이 작다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<CategoryEntity> findAllByOrderByIdAsc();
}
