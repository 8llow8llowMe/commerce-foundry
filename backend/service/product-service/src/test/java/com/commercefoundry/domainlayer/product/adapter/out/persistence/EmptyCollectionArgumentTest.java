package com.commercefoundry.domainlayer.product.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.ProductImageRepository;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.ProductOptionGroupRepository;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.ProductOptionValueRepository;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.ProductRepository;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.SkuRepository;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.custom.ProductCustomRepositoryImpl;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.custom.SkuCustomRepositoryImpl;
import com.commercefoundry.domainlayer.product.application.mapper.ProductImageMapper;
import com.commercefoundry.domainlayer.product.application.mapper.ProductMapper;
import com.commercefoundry.domainlayer.product.application.mapper.ProductOptionMapper;
import com.commercefoundry.domainlayer.product.application.mapper.SkuMapper;
import com.commercefoundry.domainlayer.product.application.model.ProductSearchCriteria;
import com.commercefoundry.domainlayer.product.application.model.ProductSortType;
import com.commercefoundry.domainlayer.product.application.port.out.query.ProductListQueryResult;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Slice;

/**
 * 빈 컬렉션 인자는 DB 에 가지 않고 빈 결과를 낸다 — {@code in ()} 은 방언에 따라 문법 오류이고, 가도 결과는 늘 비어 있다.
 * 실제 질의 결과는 PostgreSQL 리포지토리 테스트({@code ProductPersistenceAdapterTest})가 본다.
 */
class EmptyCollectionArgumentTest {

    @Test
    @DisplayName("상품 아이디 목록 조회")
    void products() {
        ProductRepository repository = mock(ProductRepository.class);
        ProductMapper mapper = mock(ProductMapper.class);

        assertThat(new ProductRepositoryAdapter(repository, mapper).findAllByIds(List.of())).isEmpty();
        verifyNoInteractions(repository, mapper);
    }

    @Test
    @DisplayName("옵션 그룹 · 값 아이디 목록 조회")
    void options() {
        ProductOptionGroupRepository groupRepository = mock(ProductOptionGroupRepository.class);
        ProductOptionValueRepository valueRepository = mock(ProductOptionValueRepository.class);
        ProductOptionMapper mapper = mock(ProductOptionMapper.class);
        ProductOptionRepositoryAdapter adapter = new ProductOptionRepositoryAdapter(groupRepository, valueRepository, mapper);

        assertThat(adapter.findGroupsByIds(Set.of())).isEmpty();
        assertThat(adapter.findValuesByIds(List.of())).isEmpty();
        verifyNoInteractions(groupRepository, valueRepository, mapper);
    }

    @Test
    @DisplayName("SKU 아이디 목록 조회 · 상품별 집계")
    void skus() {
        SkuRepository repository = mock(SkuRepository.class);
        SkuMapper mapper = mock(SkuMapper.class);
        SkuRepositoryAdapter adapter = new SkuRepositoryAdapter(repository, mapper);

        assertThat(adapter.findAllByIds(List.of())).isEmpty();
        assertThat(adapter.aggregateByProductIds(List.of())).isEmpty();
        verifyNoInteractions(repository, mapper);
    }

    @Test
    @DisplayName("대표 이미지 조회")
    void thumbnails() {
        ProductImageRepository repository = mock(ProductImageRepository.class);
        ProductImageMapper mapper = mock(ProductImageMapper.class);

        assertThat(new ProductImageRepositoryAdapter(repository, mapper).findThumbnailsByProductIds(List.of())).isEmpty();
        verifyNoInteractions(repository, mapper);
    }

    @Test
    @DisplayName("QueryDSL 구현도 직접 불리면 같은 규칙을 지킨다 — 빈 카테고리 필터는 빈 Slice, 빈 상품 목록 집계는 빈 목록")
    void customRepositories() {
        JPAQueryFactory queryFactory = mock(JPAQueryFactory.class);

        Slice<ProductListQueryResult> slice = new ProductCustomRepositoryImpl(queryFactory)
            .searchVisible(new ProductSearchCriteria(Set.of(), ProductSortType.NEWEST, 0, 20));
        assertThat(slice.getContent()).isEmpty();
        assertThat(slice.hasNext()).isFalse();
        assertThat(new SkuCustomRepositoryImpl(queryFactory).aggregateByProductIds(List.of())).isEmpty();
        verifyNoInteractions(queryFactory);
    }
}
