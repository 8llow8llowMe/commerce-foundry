package com.commercefoundry.domainlayer.productdraft.application.port.out.query;

/**
 * 초안 이미지 관리 키 한 장의 저장소 확인 결과. 저장소 꺼짐 · 장애는 결과가 아니라 503({@code PRODUCT_DRAFT_501}) 예외다.
 * {@code head} 는 {@link Stored} · {@link Missing} 만, {@code read} 는 {@link Read} · {@link Missing} · {@link Invalid} · {@link Changed} 만 돌려준다.
 */
public sealed interface ProductDraftImageObjectQueryResult {

    /**
     * HEAD 값.
     *
     * @param contentType 저장된 Content-Type 을 이미지 형식으로 정규화한 값. JPEG · PNG · WebP 가 아니면 null
     * @param eTag        저장소가 준 그대로(따옴표 포함)
     */
    record Stored(long length, String contentType, String eTag) implements ProductDraftImageObjectQueryResult {
        @Override
        public String toString() {
            return "Stored[redacted]";
        }
    }

    /** 주어진 ETag 로 묶어 끝까지 읽었고 맨 앞 magic bytes 가 JPEG · PNG · WebP 다. */
    record Read(byte[] bytes, String contentType) implements ProductDraftImageObjectQueryResult {
        @Override
        public String toString() {
            return "Read[redacted]";
        }
    }

    /** 객체가 없다. */
    record Missing() implements ProductDraftImageObjectQueryResult {
    }

    /** 비었거나 상한보다 크거나 허용 형식이 아니다(GIF · SVG · HTML 등). */
    record Invalid() implements ProductDraftImageObjectQueryResult {
    }

    /** 같은 키의 내용이 주어진 ETag 와 다르다. */
    record Changed() implements ProductDraftImageObjectQueryResult {
    }
}
