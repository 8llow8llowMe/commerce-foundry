package com.commercefoundry.domainlayer.category.adapter.in.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.commercefoundry.domainlayer.category.adapter.in.web.dto.item.CategoryItem;
import com.commercefoundry.domainlayer.category.adapter.in.web.dto.response.CategoryTreeResponse;
import com.commercefoundry.domainlayer.category.application.port.in.CategoryWebUseCase;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import com.commercefoundry.security.token.JwtVerificationPropertiesConfig;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@WebMvcTest(CategoryWebController.class)
@Import({ResourceServerSecurityConfigurer.class, JwtVerificationPropertiesConfig.class})
class CategoryWebControllerTest {

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private CategoryWebUseCase categoryWebUseCase;

    @Test
    @DisplayName("익명으로 200 이고 {categories: [...]} 를 Response 봉투에 싣는다 — 아이디는 문자열, 하위는 children")
    void tree() {
        given(categoryWebUseCase.getCategoryTree()).willReturn(CategoryTreeResponse.builder().categories(List.of(
            CategoryItem.builder().categoryId("101").name("상의").displayOrder(1)
                .children(List.of(CategoryItem.builder().categoryId("102").name("저고리").displayOrder(1).children(List.of()).build()))
                .build()
        )).build());

        MvcTestResult result = mvc.get().uri("/api/v1/categories").exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.dataHeader.success").isEqualTo(true);
        assertThat(result).bodyJson().extractingPath("$.dataHeader.resultCode").isNull();
        assertThat(result).bodyJson().extractingPath("$.dataBody.categories[0].categoryId").isEqualTo("101");
        assertThat(result).bodyJson().extractingPath("$.dataBody.categories[0].displayOrder").isEqualTo(1);
        assertThat(result).bodyJson().extractingPath("$.dataBody.categories[0].children[0].categoryId").isEqualTo("102");
        assertThat(result).bodyJson().extractingPath("$.dataBody.categories[0].children[0].children").asArray().isEmpty();
    }

    @Test
    @DisplayName("카테고리가 없으면 categories: [] 다")
    void empty() {
        given(categoryWebUseCase.getCategoryTree()).willReturn(CategoryTreeResponse.builder().categories(List.of()).build());

        MvcTestResult result = mvc.get().uri("/api/v1/categories").exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.dataBody.categories").asArray().isEmpty();
    }
}
