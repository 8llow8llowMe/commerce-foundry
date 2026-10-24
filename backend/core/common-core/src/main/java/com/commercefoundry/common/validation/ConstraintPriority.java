package com.commercefoundry.common.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 커스텀 제약 애노테이션이 검증 오류 정렬에서 어느 자리(필수 → 길이 → 범위 → 형식)에 서는지 밝히는 메타 애노테이션 (#261).
 *
 * <p>{@code ValidationErrorSupport} 는 표준 제약({@code @NotBlank} · {@code @Size} · {@code @Email} …)의 자리를 이름으로 안다.
 * 서비스가 만든 제약은 이름을 모르므로 이 애노테이션을 제약 애노테이션 위에 달아 스스로 밝힌다. 달지 않으면 표준 제약 모두의 뒤에 선다.
 * common-core 에 서비스 제약 이름을 적지 않기 위한 방식이다.
 *
 * <pre>{@code
 * @ConstraintPriority(ConstraintPriority.Level.LENGTH)
 * @Constraint(validatedBy = NormalizedLengthValidator.class)
 * public @interface NormalizedLength { ... }
 * }</pre>
 */
@Documented
@Target(ElementType.ANNOTATION_TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ConstraintPriority {

    Level value();

    /** 정렬 자리. 값이 작을수록 먼저 — 사용자가 먼저 고쳐야 하는 것을 앞에 둔다. */
    enum Level {
        REQUIRED(0),
        LENGTH(1),
        RANGE(2),
        FORMAT(3);

        private final int order;

        Level(int order) {
            this.order = order;
        }

        public int order() {
            return order;
        }
    }
}
