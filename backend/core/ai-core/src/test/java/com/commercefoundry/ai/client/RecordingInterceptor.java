package com.commercefoundry.ai.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.ai.metering.AiCallContext;
import com.commercefoundry.ai.metering.AiCallHandle;
import com.commercefoundry.ai.metering.AiCallInterceptor;
import com.commercefoundry.ai.metering.AiCallOutcome;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** 대화 · 스트리밍 테스트가 같이 쓰는 계량 기록기. before 가 돌려준 핸들이 after 로 그대로 오는지도 본다. */
final class RecordingInterceptor implements AiCallInterceptor {

    record Handle(String callId) implements AiCallHandle {

    }

    final List<AiCallContext> contexts = new CopyOnWriteArrayList<>();
    final List<AiCallHandle> issued = new CopyOnWriteArrayList<>();
    final List<AiCallHandle> handles = new CopyOnWriteArrayList<>();
    final List<AiCallOutcome> outcomes = new CopyOnWriteArrayList<>();
    private final Function<AiCallContext, AiCallHandle> onBefore;

    RecordingInterceptor() {
        this(context -> new Handle(context.callId()));
    }

    RecordingInterceptor(Function<AiCallContext, AiCallHandle> onBefore) {
        this.onBefore = onBefore;
    }

    @Override
    public AiCallHandle before(AiCallContext context) {
        contexts.add(context);
        AiCallHandle handle = onBefore.apply(context);
        issued.add(handle);
        return handle;
    }

    @Override
    public void after(AiCallHandle handle, AiCallOutcome outcome) {
        handles.add(handle);
        outcomes.add(outcome);
    }

    /** before · after 가 정확히 한 번씩이고 핸들이 이어졌다. */
    AiCallOutcome single() {
        assertThat(contexts).hasSize(1);
        assertThat(outcomes).hasSize(1);
        assertThat(handles).containsExactlyElementsOf(issued);
        return outcomes.getFirst();
    }
}
