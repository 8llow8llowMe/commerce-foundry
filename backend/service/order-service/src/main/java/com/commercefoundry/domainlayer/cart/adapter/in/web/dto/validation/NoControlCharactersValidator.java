package com.commercefoundry.domainlayer.cart.adapter.in.web.dto.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * {@link NoControlCharacters} 판정. 한 값에 위반 하나만 낸다 — 몇 글자가 걸리든 첫 글자에서 멈춘다.
 *
 * <ul>
 *   <li><b>제어 문자</b>({@code \p{Cc}}) — PostgreSQL text 는 NUL(0x00)을 받지 않아 저장이 500 이 되고(#232), 나머지는 화면 · 주문서에 그대로 찍히거나 보이지 않는다.
 *       {@code strip()} 은 탭 · 개행은 지우지만 NUL 은 남기므로 자르기 전 원문으로 본다.</li>
 *   <li><b>짝 없는 서로게이트</b> — JSON 유니코드 이스케이프로 서로게이트(U+D800~U+DFFF) 하나만 보내면 들어온다. PostgreSQL 이 거절하지는 않지만 pgjdbc 가 UTF-8 로 바꾸며 {@code ?} 로
 *       치환해, 입력과 다른 값이 조용히 저장된다.</li>
 *   <li>서식 문자({@code \p{Cf}})는 막지 않는다 — 이모지 ZWJ 시퀀스(U+200D) 같은 정상 입력이 여기 든다.</li>
 * </ul>
 */
public class NoControlCharactersValidator implements ConstraintValidator<NoControlCharacters, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || value.codePoints().noneMatch(NoControlCharactersValidator::isRejected);
    }

    /** {@code codePoints()} 는 짝 없는 서로게이트를 그 코드 유닛 하나로 낸다 — 유형이 {@code SURROGATE} 다. */
    private static boolean isRejected(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.CONTROL || type == Character.SURROGATE;
    }
}
