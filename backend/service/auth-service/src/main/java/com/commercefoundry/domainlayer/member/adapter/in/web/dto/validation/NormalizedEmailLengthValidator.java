package com.commercefoundry.domainlayer.member.adapter.in.web.dto.validation;

import com.commercefoundry.domainlayer.member.domain.model.Member;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * {@link ValidNormalizedEmailLength} 판정. "원문은 {@value Member#EMAIL_MAX_LENGTH}자 이하인데 정규화하면 넘는" 값만 거절한다.
 *
 * <ul>
 *   <li>null · 공백만인 값은 통과 — {@code @NotBlank}(AUTH_101) 몫이다.</li>
 *   <li>원문이 이미 넘는 값도 통과 — {@code @Size}(AUTH_102) 가 잡는다. 같은 칸에 AUTH_102 가 두 번 나오지 않게 한다.</li>
 *   <li>정규화는 도메인 {@link Member#canonicalEmail} 을 그대로 쓴다 — 저장 · 조회 키를 만드는 {@link Member#normalizeEmail} 과 기준이 갈라지지 않는다.
 *       길이는 둘 다 {@link String#length()}(UTF-16 단위)이고 {@code @Size} 도 같다.</li>
 * </ul>
 */
public class NormalizedEmailLengthValidator implements ConstraintValidator<ValidNormalizedEmailLength, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank() || value.length() > Member.EMAIL_MAX_LENGTH) {
            return true;
        }
        return Member.canonicalEmail(value).length() <= Member.EMAIL_MAX_LENGTH;
    }
}
