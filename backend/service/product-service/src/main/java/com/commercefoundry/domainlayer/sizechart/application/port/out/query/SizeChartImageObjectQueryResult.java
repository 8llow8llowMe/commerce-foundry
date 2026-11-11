package com.commercefoundry.domainlayer.sizechart.application.port.out.query;

/** 관리 키 이미지 한 장의 상한 읽기 결과. 저장소 장애는 결과가 아니라 503 예외다. */
public sealed interface SizeChartImageObjectQueryResult {

    /** HEAD 의 ETag 로 묶어 끝까지 읽었고 magic bytes 가 JPEG · PNG · WebP 다. */
    record Found(byte[] bytes, String contentType) implements SizeChartImageObjectQueryResult {
        @Override
        public String toString() {
            return "Found[redacted]";
        }
    }

    /** 객체가 없다. */
    record Missing() implements SizeChartImageObjectQueryResult {
    }

    /** 비었거나 상한보다 크거나 허용 형식이 아니다(GIF · SVG 등). */
    record Invalid() implements SizeChartImageObjectQueryResult {
    }

    /** 확인 중 같은 키의 내용이 바뀌었다. */
    record Changed() implements SizeChartImageObjectQueryResult {
    }
}
