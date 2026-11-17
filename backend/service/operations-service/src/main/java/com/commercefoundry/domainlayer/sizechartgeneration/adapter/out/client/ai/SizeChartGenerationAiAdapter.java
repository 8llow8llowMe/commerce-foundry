package com.commercefoundry.domainlayer.sizechartgeneration.adapter.out.client.ai;

import com.commercefoundry.ai.client.AiClient;
import com.commercefoundry.ai.client.AiException;
import com.commercefoundry.ai.client.AiImage;
import com.commercefoundry.ai.prompt.PromptTemplates;
import com.commercefoundry.ai.prompt.PromptValues;
import com.commercefoundry.domainlayer.sizechartgeneration.application.model.SizeChartGenerationRemoteFailure;
import com.commercefoundry.domainlayer.sizechartgeneration.application.model.SizeChartGenerationRemoteFailure.Code;
import com.commercefoundry.domainlayer.sizechartgeneration.application.port.out.SizeChartGenerationAiPort;
import com.commercefoundry.domainlayer.sizechartgeneration.domain.model.SizeChartGenerationCandidate;
import com.commercefoundry.domainlayer.sizechartgeneration.domain.model.SizeChartImageGenerationCandidate;
import java.util.List;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * AI 사이즈표 추출. TEXT 는 기능 {@code size-chart}, 승인한 IMAGE 는 기능 {@code size-chart-image}(프롬프트 {@code ai/prompts/<기능>/v<버전>})다.
 * 사용량 원장은 ai-core 계량 인터셉터가 {@code job:<jobId>:<attempt>} 로 남긴다. provider 오류 원문 · 출력 원문 · 이미지 바이트는 실패 · 로그에 싣지 않는다.
 */
@Component
@RequiredArgsConstructor
public class SizeChartGenerationAiAdapter implements SizeChartGenerationAiPort {

    public static final String TEXT_FEATURE = "size-chart";
    public static final String IMAGE_FEATURE = "size-chart-image";

    private final AiClient aiClient;
    private final PromptTemplates templates;

    @Override
    public SizeChartGenerationCandidate generate(String sourceJson, String templateJson, long jobId, int attempt) {
        return guarded(() -> {
            var request = templates.get(TEXT_FEATURE).render(PromptValues.create()
                .put("sourceJson", sourceJson).put("templateJson", templateJson)).withCallerRef("job:" + jobId + ":" + attempt);
            return aiClient.complete(request, SizeChartGenerationCandidate.class).value();
        });
    }

    @Override
    public SizeChartImageGenerationCandidate generateFromImages(String imagesJson, String templateJson, List<Attachment> images, long jobId,
                                                                int attempt) {
        return guarded(() -> {
            // 복사는 AiImage 생성자의 한 번뿐이다 — 받은 바이트의 내부 배열을 바로 넘긴다
            List<AiImage> attached = images.stream()
                .map(image -> image.image().withBytes(bytes -> new AiImage(image.mediaType(), bytes))).toList();
            var request = templates.get(IMAGE_FEATURE).render(PromptValues.create()
                    .put("imagesJson", imagesJson).put("templateJson", templateJson))
                .withImages(attached).withCallerRef("job:" + jobId + ":" + attempt);
            return aiClient.complete(request, SizeChartImageGenerationCandidate.class).value();
        });
    }

    private static <T> T guarded(Supplier<T> call) {
        try {
            return call.get();
        } catch (AiException exception) {
            // Fixed codes only. Even provider errorType/feature may contain untrusted text.
            throw new SizeChartGenerationRemoteFailure(Code.SIZE_CHART_AI_FAILED, exception.reason().retryable());
        } catch (IllegalArgumentException exception) {
            throw new SizeChartGenerationRemoteFailure(Code.SIZE_CHART_AI_INVALID, false);
        }
    }
}
