package com.commercefoundry.domainlayer.storeprofile.adapter.in.web.controller;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.storeprofile.adapter.in.web.dto.response.StoreProfileWebResponse;
import com.commercefoundry.domainlayer.storeprofile.application.port.in.StoreProfileWebUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "쇼핑몰 공개 정보", description = "공개 사업자·고객센터 정보")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/store/profile")
public class StoreProfileWebController {

    private final StoreProfileWebUseCase useCase;

    @Operation(summary = "쇼핑몰 공개 정보 조회", description = "미등록은 404입니다.")
    @PreAuthorize("permitAll()")
    @GetMapping
    public ResponseEntity<Response<StoreProfileWebResponse>> get() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Response.success(useCase.get()));
    }
}
