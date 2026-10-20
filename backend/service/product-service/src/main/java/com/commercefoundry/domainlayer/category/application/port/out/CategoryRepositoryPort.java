package com.commercefoundry.domainlayer.category.application.port.out;

import com.commercefoundry.domainlayer.category.domain.model.Category;
import java.util.List;

public interface CategoryRepositoryPort {

    /** 숨김 포함 전체. 계층은 {@code CategoryTree} 가 메모리에서 만든다. */
    List<Category> findAll();

    /**
     * 어드민 쓰기용 숨김 포함 전체 — 모든 카테고리 행에 쓰기 잠금을 건 뒤 읽는다. 트랜잭션이 끝날 때까지 다른 어드민 쓰기가 기다린다.
     * 동시에 두 카테고리를 서로의 아래로 옮겨 순환이 생기거나, 삭제 판정 뒤 그 아래에 하위가 생기는 것을 막는다. 트랜잭션 안에서 부른다.
     */
    List<Category> findAllForUpdate();

    /** 새 카테고리 INSERT. 아이디는 호출한 쪽이 발급했다. */
    void insert(Category category);

    /** 이미 있는 카테고리의 상위 · 이름 · 노출 순서 · 노출 여부를 바꾼다. 아이디가 없으면 {@link IllegalStateException} 이다. */
    void update(Category category);

    void deleteById(long categoryId);
}
