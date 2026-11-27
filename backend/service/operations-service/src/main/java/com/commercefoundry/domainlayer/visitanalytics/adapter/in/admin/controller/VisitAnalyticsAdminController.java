package com.commercefoundry.domainlayer.visitanalytics.adapter.in.admin.controller;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.visitanalytics.adapter.in.admin.dto.response.VisitAnalyticsResponses.Day;
import com.commercefoundry.domainlayer.visitanalytics.adapter.in.admin.dto.response.VisitAnalyticsResponses.Product;
import com.commercefoundry.domainlayer.visitanalytics.adapter.in.support.VisitAnalyticsHttpErrors;
import com.commercefoundry.domainlayer.visitanalytics.application.port.in.VisitAnalyticsUseCase;
import com.commercefoundry.security.principal.AdminAuthority;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "[어드민] 방문 분석", description = "상품 조회수 순위와 일간 집계")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/analytics/products")
public class VisitAnalyticsAdminController {

    private static final String ANALYTICS_READ = AdminAuthority.REPORT_READ + " and hasAnyRole('ADMIN','OWNER')";

    private final VisitAnalyticsUseCase useCase;

    @Operation(summary = "기간별 상품 조회수 상위 20개", description = "한국 날짜 기준 from과 to를 포함하며 최대 31일을 조회합니다.")
    @PreAuthorize(ANALYTICS_READ)
    @GetMapping("/top")
    public ResponseEntity<Response<List<Product>>> top(
        @RequestParam(required = false) String from, @RequestParam(required = false) String to
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(Response.success(useCase.topProducts(date(from), date(to))));
    }

    @Operation(summary = "상품의 일간 조회수", description = "한국 날짜 기준 from과 to를 포함합니다. 조회수가 0인 날짜는 생략합니다.")
    @PreAuthorize(ANALYTICS_READ)
    @GetMapping("/{productId}/daily")
    public ResponseEntity<Response<List<Day>>> daily(
        @PathVariable String productId, @RequestParam(required = false) String from, @RequestParam(required = false) String to
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(Response.success(useCase.dailyProduct(productId(productId), date(from), date(to))));
    }

    private static LocalDate date(String value) {
        if (value != null && value.length() == 10) {
            try {
                return LocalDate.parse(value);
            } catch (DateTimeParseException ignored) {
                // 날짜 원문을 응답과 로그에 기록하지 않는다.
            }
        }
        throw VisitAnalyticsHttpErrors.invalidPeriod();
    }

    private static long productId(String value) {
        if (value.matches("[1-9][0-9]{0,18}")) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException ignored) {
                // long 범위를 넘은 상품 식별자다.
            }
        }
        throw VisitAnalyticsHttpErrors.invalidProduct();
    }
}
