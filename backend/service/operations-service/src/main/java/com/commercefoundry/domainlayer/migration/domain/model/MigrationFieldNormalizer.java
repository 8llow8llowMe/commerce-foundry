package com.commercefoundry.domainlayer.migration.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 원본 칸을 이 엔진의 값 규칙으로 바꾼다 ({@code migration-guide.md} §5). 값 규칙은 대상 테이블의 칼럼 · check 제약과 같다 —
 * 어기는 값은 이관 전에 이슈로 걸러, 대상 서비스에서 400 · 제약 위반으로 터지지 않게 한다. 실패는 예외가 아니라 {@link Normalized} 의 이유 코드다.
 */
public final class MigrationFieldNormalizer {

    /** {@code product.name varchar(100)} — 코드 포인트로 센다. */
    public static final int PRODUCT_NAME_MAX_LENGTH = 100;
    /** 이메일 상한 (RFC 5321). 회원 이메일 칼럼과 같다. */
    public static final int EMAIL_MAX_LENGTH = 254;

    private static final Pattern CONTROL_OR_FORMAT = Pattern.compile("[\\p{Cc}\\p{Cf}]");
    private static final Pattern PRICE_DECORATION = Pattern.compile("[,\\s원]");
    private static final Pattern DIGITS = Pattern.compile("[0-9]+");
    private static final Pattern HS_CODE_SEPARATORS = Pattern.compile("[.\\-\\s]");
    /** {@code ck_sku_hs_code_format} 과 같다. */
    private static final Pattern HS_CODE = Pattern.compile("[0-9]{6,10}");
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s.]+");

    private MigrationFieldNormalizer() {
    }

    /** 앞뒤 공백을 빼고 1~100자. 제어 · 서식 문자는 거절한다. 자동으로 자르지 않는다 — 잘린 상품명은 사람이 봐야 한다. */
    public static Normalized<String> productName(String raw) {
        String name = raw == null ? "" : raw.strip();
        if (name.isEmpty()) {
            return Normalized.failed(MigrationIssueCode.NAME_REQUIRED);
        }
        if (CONTROL_OR_FORMAT.matcher(name).find()) {
            return Normalized.failed(MigrationIssueCode.NAME_INVALID_CHARACTER);
        }
        if (name.codePointCount(0, name.length()) > PRODUCT_NAME_MAX_LENGTH) {
            return Normalized.failed(MigrationIssueCode.NAME_TOO_LONG);
        }
        return Normalized.of(name);
    }

    /** 원 단위 0 이상 정수. 쉼표 · 공백 · "원" 은 뺀다. 소수 · 음수 · 숫자 아님은 거절한다. */
    public static Normalized<Long> krwAmount(String raw) {
        String amount = raw == null ? "" : PRICE_DECORATION.matcher(raw).replaceAll("");
        if (amount.isEmpty()) {
            return Normalized.failed(MigrationIssueCode.PRICE_REQUIRED);
        }
        if (!DIGITS.matcher(amount).matches() || amount.length() > 15) {
            return Normalized.failed(MigrationIssueCode.PRICE_INVALID);
        }
        return Normalized.of(Long.parseLong(amount));
    }

    /** 선택 칸 금액(정가 등). 비었으면 빈 결과다. */
    public static Normalized<Long> optionalKrwAmount(String raw) {
        return raw == null || raw.isBlank() ? Normalized.empty() : krwAmount(raw);
    }

    /** 점 · 하이픈 · 공백을 뺀 숫자 6~10자리. 아니면 비우고 경고한다. 빈 값은 빈 결과다. */
    public static Normalized<String> hsCode(String raw) {
        if (raw == null || raw.isBlank()) {
            return Normalized.empty();
        }
        String code = HS_CODE_SEPARATORS.matcher(raw).replaceAll("");
        return HS_CODE.matcher(code).matches() ? Normalized.of(code) : Normalized.failed(MigrationIssueCode.HS_CODE_INVALID);
    }

    /** 그램 정수로 반올림한다. 0 이하 · 숫자 아님은 비우고 경고한다({@code ck_sku_weight_grams_positive}). 빈 값은 빈 결과다. */
    public static Normalized<Integer> weightGrams(String raw, WeightUnit unit) {
        if (raw == null || raw.isBlank()) {
            return Normalized.empty();
        }
        try {
            BigDecimal grams = unit.toGrams(new BigDecimal(raw.strip().replace(",", ""))).setScale(0, RoundingMode.HALF_UP);
            if (grams.signum() <= 0 || grams.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
                return Normalized.failed(MigrationIssueCode.WEIGHT_INVALID);
            }
            return Normalized.of(grams.intValueExact());
        } catch (NumberFormatException | ArithmeticException exception) {
            return Normalized.failed(MigrationIssueCode.WEIGHT_INVALID);
        }
    }

    /** 앞뒤 공백을 빼고 소문자로 — 254자 이하, {@code @} 하나와 점 있는 도메인. 회원 이메일 유니크는 이 값으로 견준다. */
    public static Normalized<String> email(String raw) {
        String email = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        if (email.isEmpty()) {
            return Normalized.failed(MigrationIssueCode.EMAIL_REQUIRED);
        }
        if (email.length() > EMAIL_MAX_LENGTH || !EMAIL.matcher(email).matches()) {
            return Normalized.failed(MigrationIssueCode.EMAIL_INVALID);
        }
        return Normalized.of(email);
    }
}
