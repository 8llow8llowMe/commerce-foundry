package com.commercefoundry.domainlayer.look.adapter.in.admin.presenter;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.look.adapter.in.admin.dto.response.LookAdminResponse;
import com.commercefoundry.domainlayer.look.application.info.LookAdminInfo;
import com.commercefoundry.domainlayer.look.application.info.LookProductAdminInfo;
import com.commercefoundry.domainlayer.look.domain.model.LookImage;
import com.commercefoundry.domainlayer.product.domain.model.SaleStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

class LookAdminPresenterTest {

    private final LookAdminPresenter presenter = new LookAdminPresenter();

    @Test
    void deletedFlagReachesSingleAndListResponses() {
        LookAdminInfo info = new LookAdminInfo(1L, "룩", null, new LookImage("https://cdn.example.com/a.jpg", null, null, null), true,
            List.of(
                new LookProductAdminInfo(100L, "삭제 상품", false, SaleStatus.STOP_SELLING, true),
                new LookProductAdminInfo(200L, "정상 상품", true, SaleStatus.ON_SALE, false)
            ));

        LookAdminResponse single = presenter.toResponse(info);

        assertThat(single.products()).extracting(product -> product.deleted()).containsExactly(true, false);
        assertThat(presenter.toListResponse(List.of(info)).looks().getFirst().products())
            .extracting(product -> product.deleted()).containsExactly(true, false);
    }
}
