package com.commercefoundry.domainlayer.sizechartgeneration.application.port.out;

import com.commercefoundry.domainlayer.sizechartgeneration.application.port.out.query.SizeChartGenerationImageQueryResult;
import com.commercefoundry.domainlayer.sizechartgeneration.application.port.out.query.SizeChartGenerationInputQueryResult;
import java.util.List;
import java.util.function.Consumer;

/** product 가 고정한 사이즈표 생성 입력 · IMAGE 바이트. 실패는 {@code SizeChartGenerationRemoteFailure} 로만 알린다. */
public interface SizeChartGenerationInputPort {

    SizeChartGenerationInputQueryResult getGenerationInput(long draftId);

    /**
     * {@code indexes} 순서대로 이미지 바이트를 한 장씩 읽고, 받을 때마다 다음 장을 읽기 전에 {@code onImage} 에 넘긴다 — 호출자가 장마다 해시 검증 ·
     * checkpoint 를 둔다. {@code onImage} 가 던지면 거기서 멈추고 그대로 내보낸다.
     *
     * <p>응답 {@code Content-Length} 가 없거나 장당 상한을 넘거나, {@code Content-Type} 이 계약({@code application/octet-stream}) 밖이거나, 본문 길이가
     * {@code Content-Length} 와 다르면 본문을 더 읽지 않고 {@code SIZE_CHART_IMAGE_CHANGED}(재시도 불가)다.
     *
     * @param maxBytes 장당 바이트 상한. 이보다 길게 받지 않는다
     */
    void readGenerationImages(long draftId, List<Integer> indexes, int maxBytes, Consumer<SizeChartGenerationImageQueryResult> onImage);
}
