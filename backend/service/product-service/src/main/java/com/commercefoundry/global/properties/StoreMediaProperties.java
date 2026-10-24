package com.commercefoundry.global.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 스토어 미디어(이미지 · 영상) 주소 설정. 어드민이 저장하는 미디어 주소는 이 공개 주소 아래만 받는다 — 판정은 {@code MediaUrlPolicy}.
 *
 * <p>값은 오브젝트 스토리지의 공개 읽기 주소({@code STORAGE_PUBLIC_BASE_URL}, {@code bootstrap-conventions.md} Storage)와 같다. product-service 는
 * storage-core 를 아직 쓰지 않아 {@code storage.public-base-url} 대신 이 키로 받는다 — 상품 이미지(#9 PR-F)가 storage-core 를 붙이면 같은 환경변수를 읽는다.
 *
 * @param publicBaseUrl 공개 읽기 주소. 끝의 {@code /} 는 지운다. 비면 미디어를 받지 않는다(닫힌 기본값)
 */
@ConfigurationProperties(prefix = "store.media")
public record StoreMediaProperties(String publicBaseUrl) {

    public StoreMediaProperties {
        publicBaseUrl = normalize(publicBaseUrl);
    }

    /** 앞뒤 공백을 빼고 끝의 {@code /} 를 모두 지운다. 비거나 null 이면 빈 문자열이다. */
    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String stripped = value.strip();
        int end = stripped.length();
        while (end > 0 && stripped.charAt(end - 1) == '/') {
            end--;
        }
        return stripped.substring(0, end);
    }
}
