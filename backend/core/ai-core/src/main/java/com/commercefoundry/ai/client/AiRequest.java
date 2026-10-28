package com.commercefoundry.ai.client;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 호출 한 번의 입력. 기능 쪽은 보통 {@code PromptTemplate.render} 로 만든다.
 *
 * @param feature         기능 이름({@code size-chart} 등). 모델 · 토큰 설정을 고르고 로그 · 사용량을 나누는 키다
 * @param system          시스템 프롬프트. 없으면 {@code null}
 * @param prompt          사용자 메시지
 * @param sensitiveValues 호출자가 아는 개인정보 값(받는 사람 이름 · 주소 · 연락처 · 주문번호 등). 보내기 전에 가린다 —
 *                        이름처럼 정규식으로 잡을 수 없는 값은 여기로 넘긴다. 두 글자 미만은 무시한다(한 글자를 가리면 본문이 깨진다)
 * @param promptVersion   이 요청을 만든 템플릿 버전. 템플릿 없이 만들었으면 {@code null}. 로그에 남겨 결과를 버전과 대조한다
 * @param images          사용자 메시지에 붙이는 이미지. 없으면 빈 목록. 글자 마스킹은 이미지에 적용되지 않는다
 * @param callerRef       호출한 작업 · 업무의 식별자({@code job:123} · {@code job:123:2} 등). 없으면 {@code null}. 사용량 원장이 호출을 작업과 잇는 키다 —
 *                        <b>내부 PK(jobId 등)만</b> 넣는다(주문번호 · 연락처 · 이메일 · 이름 금지). {@link #CALLER_REF} 형식({@code 접두어:토막}, 토막 1~3개), 100자 이하
 */
public record AiRequest(String feature, String system, String prompt, List<String> sensitiveValues, Integer promptVersion, List<AiImage> images,
                        String callerRef) {

    /** 기능 이름 규칙 — 소문자 · 숫자 · {@code -}, 1~63자. 템플릿 경로에도 들어가므로 한 곳에서 정한다. */
    public static final Pattern FEATURE_NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");
    /**
     * 호출자 식별자 규칙 — 소문자 접두어(1~31자, {@code job}) 뒤에 {@code :토막}(영문 · 숫자 · {@code _} · {@code -}, 1~64자)이 1~3개.
     * 이메일({@code @}) · 공백 · 한글 같은 자유 텍스트(개인정보)가 원장 · 로그로 들어가지 않게 좁힌다. 전체 길이는 {@link #CALLER_REF_MAX_LENGTH} 이하다.
     */
    public static final Pattern CALLER_REF = Pattern.compile("[a-z][a-z0-9-]{0,30}(?::[A-Za-z0-9_-]{1,64}){1,3}");
    public static final int CALLER_REF_MAX_LENGTH = 100;

    public AiRequest {
        Objects.requireNonNull(feature, "feature");
        if (!FEATURE_NAME.matcher(feature).matches()) {
            throw new IllegalArgumentException("feature 는 소문자 · 숫자 · '-' 로 1~63자여야 합니다");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt 가 비었습니다");
        }
        if (callerRef != null && (callerRef.length() > CALLER_REF_MAX_LENGTH || !CALLER_REF.matcher(callerRef).matches())) {
            // 값을 싣지 않는다 — 규칙을 어긴 값은 개인정보일 수 있다
            throw new IllegalArgumentException("callerRef 는 소문자 접두어 뒤에 :토막(영문 · 숫자 · _ · -) 1~3개, 100자 이하여야 합니다 (예: job:123)");
        }
        sensitiveValues = sensitiveValues == null ? List.of() : List.copyOf(sensitiveValues.stream().filter(Objects::nonNull).toList());
        images = images == null ? List.of() : List.copyOf(images);
    }

    /** 호출자 식별자 없이. */
    public AiRequest(String feature, String system, String prompt, List<String> sensitiveValues, Integer promptVersion, List<AiImage> images) {
        this(feature, system, prompt, sensitiveValues, promptVersion, images, null);
    }

    /** 템플릿 없이 만든 요청. 이미지는 없다. */
    public AiRequest(String feature, String system, String prompt, List<String> sensitiveValues) {
        this(feature, system, prompt, sensitiveValues, null, List.of(), null);
    }

    /** 템플릿이 버전만 더 알고 이미지는 없을 때. */
    public AiRequest(String feature, String system, String prompt, List<String> sensitiveValues, Integer promptVersion) {
        this(feature, system, prompt, sensitiveValues, promptVersion, List.of(), null);
    }

    public static AiRequest of(String feature, String system, String prompt) {
        return new AiRequest(feature, system, prompt, List.of(), null, List.of(), null);
    }

    /** 같은 문구에 이미지를 붙인다. {@code PromptTemplate.render} 결과에 사이즈표 이미지를 실을 때 쓴다. */
    public AiRequest withImages(List<AiImage> images) {
        return new AiRequest(feature, system, prompt, sensitiveValues, promptVersion, images, callerRef);
    }

    /** 같은 요청에 호출자 식별자를 붙인다. 작업 핸들러가 {@code "job:" + jobId} 처럼 넘긴다. */
    public AiRequest withCallerRef(String callerRef) {
        return new AiRequest(feature, system, prompt, sensitiveValues, promptVersion, images, callerRef);
    }

    /** 원문(프롬프트 · 개인정보 값 · 이미지 바이트)을 로그로 내지 않는다 — 길이와 개수만 싣는다. 호출자 식별자는 규칙상 식별자라 싣는다. */
    @Override
    public String toString() {
        return "AiRequest[feature=" + feature + ", promptVersion=" + promptVersion + ", callerRef=" + callerRef
            + ", systemLength=" + (system == null ? 0 : system.length()) + ", promptLength=" + prompt.length()
            + ", sensitiveValues=" + sensitiveValues.size() + ", images=" + images.size() + "]";
    }
}
