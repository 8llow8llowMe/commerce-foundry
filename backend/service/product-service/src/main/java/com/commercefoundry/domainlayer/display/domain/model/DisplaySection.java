package com.commercefoundry.domainlayer.display.domain.model;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Objects;

/**
 * 메인 전시 섹션 한 개(머리 정보). 아이템은 {@link DisplaySectionContent} 가 함께 든다.
 *
 * <p><b>노출 조건</b>({@link #isLiveAt}) — {@code visible} 이고, 시작 시각이 없거나 지났고({@code startAt <= now}), 종료 시각이 없거나 아직 오지 않았다({@code now < endAt}).
 * 시작은 포함 · 종료는 제외라 연달아 이어 붙인 두 기간이 한 순간에 함께 보이거나 함께 빠지지 않는다.
 *
 * @param title 제목. 없으면 null
 * @param subtitle 부제목. 없으면 null
 * @param body 본문(개행 허용). 없으면 null
 * @param linkUrl 이동 주소 — {@code /} 로 시작하는 상대 경로 또는 https 절대 주소. 없으면 null
 * @param linkLabel 이동 버튼 문구. 없으면 null (주소 없이 문구만 둘 수 없다)
 * @param displayOrder 메인 화면 안의 순서 (0 부터, 오름차순 — 같으면 아이디 오름차순)
 * @param startAt 노출 시작 시각(포함). 없으면 제한 없음
 * @param endAt 노출 종료 시각(제외). 없으면 제한 없음
 */
public record DisplaySection(
    long id, DisplaySectionType sectionType, String title, String subtitle, String body,
    String linkUrl, String linkLabel, int displayOrder, boolean visible, Instant startAt, Instant endAt
) {

    /** 저장할 수 있는 가장 이른 노출 시각 (포함). 요청 검증({@code PRODUCT_165})이 이 범위를 본다. */
    public static final Instant EARLIEST_PERIOD_AT = Instant.parse("0001-01-01T00:00:00Z");

    /** 저장할 수 있는 가장 늦은 노출 시각 (포함) — 연도 9999 까지. 그 뒤는 화면 · 직렬화 형식(4자리 연도)이 깨진다. */
    public static final Instant LATEST_PERIOD_AT = Instant.parse("9999-12-31T23:59:59.999999Z");

    /**
     * 노출 시각은 마이크로초로 자른다 — PostgreSQL {@code timestamptz} 정밀도가 마이크로초라, 자르지 않으면 판정(종료 > 시작)은 나노초 차이로 통과하고
     * 저장 뒤에는 두 값이 같아져 {@code ck_display_section_period} 에 걸리거나(500) 다시 읽은 값이 판정한 값과 달라진다.
     */
    public DisplaySection {
        Objects.requireNonNull(sectionType, "sectionType");
        if (displayOrder < 0) {
            throw new IllegalArgumentException("displayOrder 는 0 이상이어야 합니다.");
        }
        startAt = startAt == null ? null : startAt.truncatedTo(ChronoUnit.MICROS);
        endAt = endAt == null ? null : endAt.truncatedTo(ChronoUnit.MICROS);
    }

    /** 두 시각이 모두 저장 가능 범위({@link #EARLIEST_PERIOD_AT} ~ {@link #LATEST_PERIOD_AT}) 안인가. 없는 시각은 맞다. */
    public static boolean isStorablePeriodAt(Instant at) {
        return at == null || !at.isBefore(EARLIEST_PERIOD_AT) && !at.isAfter(LATEST_PERIOD_AT);
    }

    /** 지금 고객 메인에 보이는가 — {@link #liveStatusAt} 이 {@link DisplayLiveStatus#LIVE} 인가. 고객 메인 필터와 어드민 노출 상태가 같은 판정을 쓴다. */
    public boolean isLiveAt(Instant now) {
        return liveStatusAt(now) == DisplayLiveStatus.LIVE;
    }

    /**
     * 지금 노출 상태 — 숨김({@code visible=false}) → 예정({@code now < startAt}) → 종료({@code endAt <= now}) → 노출 중 순으로 처음 맞는 것이다.
     * 시작 시각과 같은 순간은 노출 중(포함), 종료 시각과 같은 순간은 종료(제외)다.
     */
    public DisplayLiveStatus liveStatusAt(Instant now) {
        Objects.requireNonNull(now, "now");
        if (!visible) {
            return DisplayLiveStatus.HIDDEN;
        }
        if (startAt != null && startAt.isAfter(now)) {
            return DisplayLiveStatus.SCHEDULED;
        }
        if (endAt != null && !now.isBefore(endAt)) {
            return DisplayLiveStatus.ENDED;
        }
        return DisplayLiveStatus.LIVE;
    }

    /** 종료가 시작보다 뒤다(둘 중 하나라도 없으면 맞다). DB 의 {@code ck_display_section_period} 와 같은 규칙이다. */
    public boolean hasValidPeriod() {
        return startAt == null || endAt == null || endAt.isAfter(startAt);
    }

    /**
     * 링크 주소 허용 판정. {@code /} 로 시작하는 상대 경로(스토어 안 이동)이거나 호스트가 있는 https 절대 주소만 받는다.
     * {@code //host}(프로토콜 상대 — 다른 호스트로 간다) · {@code javascript:} 같은 다른 스킴 · 사용자 정보({@code https://a@b}) · 공백 · 제어 문자 · 역슬래시는 거절한다.
     */
    public static boolean isAllowedLinkUrl(String url) {
        if (url == null || url.isEmpty() || url.indexOf('\\') >= 0 || url.codePoints().anyMatch(DisplaySection::isRejectedCharacter)) {
            return false;
        }
        if (url.startsWith("/")) {
            return !url.startsWith("//") && parse(url) != null;
        }
        URI uri = parse(url);
        return uri != null && uri.isAbsolute() && "https".equals(uri.getScheme().toLowerCase(Locale.ROOT))
            && uri.getHost() != null && uri.getRawUserInfo() == null;
    }

    private static boolean isRejectedCharacter(int codePoint) {
        int type = Character.getType(codePoint);
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
            || type == Character.CONTROL || type == Character.FORMAT || type == Character.SURROGATE;
    }

    private static URI parse(String value) {
        try {
            return new URI(value);
        } catch (URISyntaxException exception) {
            return null;
        }
    }
}
