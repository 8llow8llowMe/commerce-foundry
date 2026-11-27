package com.commercefoundry.domainlayer.storebrand.adapter.in.web.controller;

import com.commercefoundry.common.dto.Response;
import com.commercefoundry.domainlayer.storebrand.adapter.in.web.dto.response.BrandAssetWebResponses;
import com.commercefoundry.domainlayer.storebrand.application.port.in.BrandAssetWebUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "스토어 브랜드 이미지", description = "현재 게시한 로고·사이트 아이콘 조회")
@RestController
@RequiredArgsConstructor
@RequestMapping(BrandAssetWebResponses.PATH)
public class BrandAssetWebController {
    private final BrandAssetWebUseCase useCase;

    @Operation(summary = "현재 게시한 브랜드 이미지 링크 조회")
    @PreAuthorize("permitAll()")
    @GetMapping
    public ResponseEntity<Response<BrandAssetWebResponses.Publication>> get() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(Response.success(useCase.publication()));
    }

    @Operation(summary = "현재 게시본에 연결된 PNG 조회")
    @PreAuthorize("permitAll()")
    @GetMapping(value = "/images/{version}/{kind}/{assetId}", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> image(@PathVariable long version, @PathVariable String kind, @PathVariable UUID assetId) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
            .header("X-Content-Type-Options", "nosniff").body(useCase.image(version, kind, assetId));
    }
}
