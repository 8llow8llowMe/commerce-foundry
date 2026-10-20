package com.commercefoundry.domainlayer.category.adapter.out.persistence;

import com.commercefoundry.domainlayer.category.adapter.out.persistence.entity.CategoryEntity;
import com.commercefoundry.domainlayer.category.adapter.out.persistence.repository.CategoryRepository;
import com.commercefoundry.domainlayer.category.application.mapper.CategoryMapper;
import com.commercefoundry.domainlayer.category.application.port.out.CategoryRepositoryPort;
import com.commercefoundry.domainlayer.category.domain.model.Category;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CategoryRepositoryAdapter implements CategoryRepositoryPort {

    private final CategoryRepository categoryRepository;
    private final CategoryMapper categoryMapper;

    @Override
    public List<Category> findAll() {
        return categoryMapper.toDomains(categoryRepository.findAll());
    }

    /**
     * 잠금과 읽기를 두 문장으로 나눈다. PostgreSQL READ COMMITTED 에서 잠금을 기다린 문장은 그 문장이 시작될 때의 스냅숏을 쓰므로,
     * 기다리는 사이 다른 어드민이 커밋한 새 카테고리가 결과에 없다. 잠금을 얻은 뒤의 두 번째 문장은 새 스냅숏이라 그 행까지 읽는다 —
     * 삭제 판정이 막 생긴 하위를 놓치지 않는다. 이미 잠근 행은 영속성 컨텍스트의 같은 인스턴스(잠금 시점의 최신 값)로 돌아온다.
     */
    @Override
    public List<Category> findAllForUpdate() {
        categoryRepository.findAllByOrderByIdAsc();
        return categoryMapper.toDomains(categoryRepository.findAll());
    }

    /** {@link CategoryEntity} 가 {@code Persistable} 이라 새 행은 persist(INSERT) 로 간다 — 아이디가 겹치면 덮어쓰지 않고 PK 위반이다. */
    @Override
    public void insert(Category category) {
        categoryRepository.save(categoryMapper.toEntity(category));
    }

    /** 관리 엔티티를 읽어 변경 메서드로 고친다 — merge 하지 않는다. 커밋(또는 다음 질의) 때 dirty checking 으로 UPDATE 가 나간다. */
    @Override
    public void update(Category category) {
        CategoryEntity entity = categoryRepository.findById(category.id())
            .orElseThrow(() -> new IllegalStateException("수정할 카테고리가 없습니다. categoryId=" + category.id()));
        entity.change(category.parentId(), category.name(), category.displayOrder(), category.visible());
    }

    @Override
    public void deleteById(long categoryId) {
        categoryRepository.deleteById(categoryId);
    }
}
