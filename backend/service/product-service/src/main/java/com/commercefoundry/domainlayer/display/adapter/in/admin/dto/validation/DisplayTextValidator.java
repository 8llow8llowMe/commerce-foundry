package com.commercefoundry.domainlayer.display.adapter.in.admin.dto.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * {@link DisplayText} 판정. 한 값에 위반 하나만 낸다.
 *
 * <ul>
 *   <li><b>제어 문자</b>({@code \p{Cc}}) — PostgreSQL text 는 NUL(0x00)을 받지 않아 저장이 500 이 되고(선례: 장바구니 #232), 나머지는 메인 화면에 보이지 않는 글자로 찍힌다.
 *       본문만 개행 · 탭을 허용한다.</li>
 *   <li><b>짝 없는 서로게이트</b> — pgjdbc 가 UTF-8 로 바꾸며 {@code ?} 로 치환해 입력과 다른 값이 조용히 저장된다.</li>
 *   <li>서식 문자({@code \p{Cf}})는 막지 않는다 — 이모지 ZWJ 시퀀스 같은 정상 입력이 여기 든다.</li>
 * </ul>
 */
public class DisplayTextValidator implements ConstraintValidator<DisplayText, String> {

    private boolean multiline;

    @Override
    public void initialize(DisplayText constraint) {
        this.multiline = constraint.multiline();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || value.codePoints().noneMatch(this::isRejected);
    }

    /** {@code codePoints()} 는 짝 없는 서로게이트를 그 코드 유닛 하나로 낸다 — 유형이 {@code SURROGATE} 다. */
    private boolean isRejected(int codePoint) {
        if (multiline && (codePoint == '\n' || codePoint == '\r' || codePoint == '\t')) {
            return false;
        }
        int type = Character.getType(codePoint);
        return type == Character.CONTROL || type == Character.SURROGATE;
    }
}
