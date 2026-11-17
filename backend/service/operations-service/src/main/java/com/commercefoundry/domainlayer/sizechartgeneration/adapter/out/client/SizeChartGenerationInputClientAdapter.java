package com.commercefoundry.domainlayer.sizechartgeneration.adapter.out.client;

import com.commercefoundry.domainlayer.sizechartgeneration.adapter.out.client.feign.SizeChartGenerationInputClient;
import com.commercefoundry.domainlayer.sizechartgeneration.adapter.out.client.support.SizeChartGenerationInputInternalResponseSupport;
import com.commercefoundry.domainlayer.sizechartgeneration.application.model.SizeChartGenerationRemoteFailure;
import com.commercefoundry.domainlayer.sizechartgeneration.application.model.SizeChartGenerationRemoteFailure.Code;
import com.commercefoundry.domainlayer.sizechartgeneration.application.port.out.SizeChartGenerationInputPort;
import com.commercefoundry.domainlayer.sizechartgeneration.application.port.out.query.SizeChartGenerationImageQueryResult;
import com.commercefoundry.domainlayer.sizechartgeneration.application.port.out.query.SizeChartGenerationInputQueryResult;
import feign.Response;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SizeChartGenerationInputClientAdapter implements SizeChartGenerationInputPort {

    /** product 이미지 바이트 응답의 계약 형식 ({@code SizeChartDraftInternalController#generationImage}). */
    static final String OCTET_STREAM = "application/octet-stream";

    private final SizeChartGenerationInputClient client;
    private final SizeChartGenerationInputInternalResponseSupport support;

    @Override
    public SizeChartGenerationInputQueryResult getGenerationInput(long draftId) {
        return support.toQueryResult(support.call(() -> client.getGenerationInput(draftId)));
    }

    /**
     * 순번마다 한 번 부른다 — product 계약이 봉투 없는 바이트 한 장씩이다(최대 4장). 한 장이라도 실패하면 거기서 멈춘다.
     * {@code onImage} 는 서킷 밖에서 부른다 — 호출자의 검증 실패 · 취소가 product 장애로 세이지 않는다.
     */
    @Override
    public void readGenerationImages(long draftId, List<Integer> indexes, int maxBytes, Consumer<SizeChartGenerationImageQueryResult> onImage) {
        for (int index : indexes) {
            SizeChartGenerationImageQueryResult received = support.call(() -> read(draftId, index, maxBytes));
            if (received == null) {
                // 헤더 · 본문이 계약과 다르다. product 는 정상 응답했으므로 서킷에는 성공으로 남는다
                throw new SizeChartGenerationRemoteFailure(Code.SIZE_CHART_IMAGE_CHANGED, false);
            }
            onImage.accept(received);
        }
    }

    /**
     * 서킷 안에서 한 장을 읽는다. 순서: 상태 → {@code Content-Length}(없거나 상한 밖이면 본문을 읽지 않음) → {@code Content-Type} → 본문을 길이 + 1 바이트까지만 읽기.
     * 계약과 다르면 null 이다. 응답 · 스트림은 여기서 닫는다.
     */
    private SizeChartGenerationImageQueryResult read(long draftId, int index, int maxBytes) {
        try (Response response = client.getGenerationImage(draftId, index)) {
            support.requireSuccess(response);
            int length = contentLength(response, maxBytes);
            if (length < 1 || !OCTET_STREAM.equals(mediaType(response))) {
                return null;
            }
            byte[] bytes = readExactly(response, length);
            return bytes == null ? null : new SizeChartGenerationImageQueryResult(index, bytes);
        } catch (IOException exception) {
            // support 가 재시도 가능한 UNAVAILABLE 로 바꾼다(원인은 싣지 않는다)
            throw new UncheckedIOException(exception);
        }
    }

    /** {@code Content-Length} 가 하나뿐이고 1 ~ {@code maxBytes} 이면 그 값, 아니면 -1. */
    private static int contentLength(Response response, int maxBytes) {
        String value = single(response.headers().get("Content-Length"));
        if (value == null || value.isEmpty() || value.length() > 10 || !value.chars().allMatch(Character::isDigit)) {
            return -1;
        }
        long length = Long.parseLong(value);
        return length >= 1 && length <= maxBytes ? (int) length : -1;
    }

    /** {@code Content-Type} 하나에서 파라미터를 뺀 소문자 형식. 없거나 여러 개면 null. */
    private static String mediaType(Response response) {
        String value = single(response.headers().get("Content-Type"));
        if (value == null) {
            return null;
        }
        int parameters = value.indexOf(';');
        String type = (parameters < 0 ? value : value.substring(0, parameters)).strip().toLowerCase(Locale.ROOT);
        return type.isEmpty() ? null : type;
    }

    private static String single(Collection<String> values) {
        return values == null || values.size() != 1 ? null : values.iterator().next();
    }

    /** 본문을 {@code length + 1} 바이트까지만 읽는다. 짧거나 길면 null — 길면 나머지를 읽지 않고 끊는다. */
    private static byte[] readExactly(Response response, int length) throws IOException {
        if (response.body() == null) {
            return null;
        }
        try (InputStream stream = response.body().asInputStream()) {
            byte[] bytes = new byte[length];
            if (stream.readNBytes(bytes, 0, length) != length || stream.read() != -1) {
                return null;
            }
            return bytes;
        }
    }
}
