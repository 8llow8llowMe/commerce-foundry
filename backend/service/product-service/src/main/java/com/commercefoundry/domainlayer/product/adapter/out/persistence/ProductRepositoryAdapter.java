package com.commercefoundry.domainlayer.product.adapter.out.persistence;

import com.commercefoundry.domainlayer.product.adapter.out.persistence.entity.ProductEntity;
import com.commercefoundry.domainlayer.product.adapter.out.persistence.repository.ProductRepository;
import com.commercefoundry.domainlayer.product.application.exception.ProductErrorCode;
import com.commercefoundry.domainlayer.product.application.exception.ProductException;
import com.commercefoundry.domainlayer.product.application.mapper.ProductMapper;
import com.commercefoundry.domainlayer.product.application.model.ProductAdminSearchCriteria;
import com.commercefoundry.domainlayer.product.application.model.ProductSearchCriteria;
import com.commercefoundry.domainlayer.product.application.port.out.ProductRepositoryPort;
import com.commercefoundry.domainlayer.product.application.port.out.query.ProductAdminListQueryResult;
import com.commercefoundry.domainlayer.product.application.port.out.query.ProductListQueryResult;
import com.commercefoundry.domainlayer.product.domain.model.Product;
import com.commercefoundry.persistence.util.ConstraintViolations;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProductRepositoryAdapter implements ProductRepositoryPort {

    /** SKU 조합 유니크 키 — 등록 판정({@code ProductWriteProcessor}) 의 2차 방어. */
    static final String UK_SKU_PRODUCT_ID_OPTION_VALUE_KEY = "uk_sku_product_id_option_value_key";

    private final ProductRepository productRepository;
    private final ProductMapper productMapper;

    @Override
    public Slice<ProductListQueryResult> searchVisible(ProductSearchCriteria criteria) {
        Set<Long> categoryIds = criteria.categoryIds();
        if (categoryIds != null && categoryIds.isEmpty()) {
            return new SliceImpl<>(List.of(), PageRequest.of(criteria.page(), criteria.size()), false);
        }
        return productRepository.searchVisible(criteria);
    }

    @Override
    public List<ProductListQueryResult> findVisibleListRowsByIds(Collection<Long> productIds) {
        if (productIds.isEmpty()) {
            return List.of();
        }
        return productRepository.findVisibleListRowsByIds(productIds);
    }

    @Override
    public Optional<Product> findVisibleById(long productId) {
        return productRepository.findVisibleById(productId).map(productMapper::toDomain);
    }

    @Override
    public List<Product> findAllByIdsIncludingHidden(Collection<Long> productIds) {
        if (productIds.isEmpty()) {
            return List.of();
        }
        return productMapper.toDomains(productRepository.findAllById(productIds));
    }

    @Override
    public Page<ProductAdminListQueryResult> searchForAdmin(ProductAdminSearchCriteria criteria) {
        Set<Long> categoryIds = criteria.categoryIds();
        if (categoryIds != null && categoryIds.isEmpty()) {
            return new PageImpl<>(List.of(), PageRequest.of(criteria.page(), criteria.size()), 0);
        }
        return productRepository.searchForAdmin(criteria);
    }

    /** 버전이 비어 있어 Spring Data 가 새 행으로 보고 persist(INSERT) 한다 — 버전은 0 으로 저장된다. */
    @Override
    public void insert(Product product) {
        productRepository.save(ProductEntity.builder()
            .id(product.id())
            .categoryId(product.categoryId())
            .name(product.name())
            .summary(product.summary())
            .descriptionHtml(product.descriptionHtml())
            .saleStatus(product.saleStatus())
            .visible(product.visible())
            .salePriceAmount(product.salePrice().amount())
            .salePriceCurrency(product.salePrice().currency())
            .listPriceAmount(product.listPrice() == null ? null : product.listPrice().amount())
            .listPriceCurrency(product.listPrice() == null ? null : product.listPrice().currency())
            .maxQuantityPerOrder(product.maxQuantityPerOrder())
            .customOrder(product.customOrder())
            .customOrderNotice(product.customOrderNotice())
            .customOrderLeadTimeDays(product.customOrderLeadTimeDays())
            .preorder(product.preorder())
            .expectedShipDate(product.expectedShipDate())
            .createdByMemberId(product.createdByMemberId())
            .build());
    }

    /** Merge the existing header and flush so version conflicts become 409 inside the transaction. */
    @Override
    public void update(Product product) {
        if (product.version() == null) {
            throw new IllegalArgumentException("Cannot update an unsaved product. productId=" + product.id());
        }
        try {
            productRepository.saveAndFlush(productMapper.toEntity(product));
        } catch (ObjectOptimisticLockingFailureException exception) {
            throw new ProductException(ProductErrorCode.PRODUCT_MODIFICATION_CONFLICT, exception);
        }
    }

    /** 영속성 컨텍스트 전체를 내보낸다 — 같은 트랜잭션의 진열 카테고리 · 옵션 · SKU INSERT 도 함께 나간다. */
    @Override
    public void flushNewProduct() {
        try {
            productRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            if (ConstraintViolations.violates(exception, UK_SKU_PRODUCT_ID_OPTION_VALUE_KEY)) {
                // 판정을 비켜 간 경우라 어느 조합인지 알 수 없다 — 메시지 자리에 일반 문구를 싣는다(원인 SQL 은 싣지 않는다)
                throw new ProductException(ProductErrorCode.PRODUCT_SKU_COMBINATION_DUPLICATED, "옵션 조합을 확인해 주세요");
            }
            throw exception;
        }
    }
}
