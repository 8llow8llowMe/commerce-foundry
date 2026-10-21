package com.commercefoundry.domainlayer.product.application.service;

import com.commercefoundry.domainlayer.category.application.service.processor.CategoryProcessor;
import com.commercefoundry.domainlayer.product.adapter.in.admin.dto.item.ProductListAdminItem;
import com.commercefoundry.domainlayer.product.adapter.in.admin.presenter.ProductAdminPresenter;
import com.commercefoundry.domainlayer.product.application.command.ProductAdminSearchCommand;
import com.commercefoundry.domainlayer.product.application.port.in.ProductAdminUseCase;
import com.commercefoundry.domainlayer.product.application.service.processor.ProductAdminQueryProcessor;
import com.commercefoundry.persistence.dto.PageResponse;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 어드민 상품 관리. 카테고리는 카테고리 컨텍스트의 {@link CategoryProcessor} 용도별 메서드로만 받는다 ({@code architecture-guide.md} §3 컨텍스트 간 의존 규칙).
 * 조회는 외부 I/O 가 없어 Facade 에 읽기 전용 트랜잭션을 건다.
 */
@Service
@RequiredArgsConstructor
public class ProductAdminFacade implements ProductAdminUseCase {

    private final CategoryProcessor categoryProcessor;
    private final ProductAdminQueryProcessor productAdminQueryProcessor;
    private final ProductAdminPresenter productAdminPresenter;

    /**
     * 카테고리 조건은 하위 카테고리까지 펼친다 — 고객 목록과 같고 숨긴 카테고리도 포함한다. 없는 아이디는 빈 집합이라 어댑터가 쿼리 없이 빈 페이지를 준다.
     *
     * <p>목록 · count · SKU 집계를 한 스냅숏으로 읽는다 — READ COMMITTED 면 문장마다 스냅숏이 달라, 그 사이 재고가 바뀌면 "품절" 필터로 고른 행이
     * "판매중" 으로 표시되거나 총계가 어긋날 수 있다. 읽기 전용이라 직렬화 실패가 없다 (어드민 재고 조회와 같은 이유).
     */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<ProductListAdminItem> searchProducts(ProductAdminSearchCommand command) {
        Set<Long> categoryIds = command.categoryId() == null ? null : categoryProcessor.subtreeIdsOf(command.categoryId());
        return productAdminPresenter.toListPageResponse(productAdminQueryProcessor.search(command, categoryIds));
    }
}
