package com.commercefoundry.security.principal;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AdminAuthorityTest {

    @Test
    @DisplayName("기능 상수는 AdminPermission 과 이름 · 권한 문자열이 1:1 이다 — 빠진 기능도, 남는 상수도 없다")
    void featureConstantsMatchPermissions() throws IllegalAccessException {
        Map<String, String> constants = constants();
        constants.remove("OPERATOR");

        Map<String, String> expected = Arrays.stream(AdminPermission.values())
            .collect(Collectors.toMap(Enum::name, permission -> "hasAuthority('" + permission.authority() + "')"));
        assertThat(constants).isEqualTo(expected);
    }

    @Test
    @DisplayName("OPERATOR 는 MemberRole.OPERATOR_ROLES 를 선언 순서 그대로 나열한 hasAnyRole 식이다")
    void operatorMatchesOperatorRoles() {
        String roles = Arrays.stream(MemberRole.operatorRoleNames()).map(name -> "'" + name + "'").collect(Collectors.joining(","));

        assertThat(AdminAuthority.OPERATOR).isEqualTo("hasAnyRole(" + roles + ")");
        assertThat(MemberRole.OPERATOR_ROLES).containsExactly(MemberRole.ADMIN, MemberRole.OWNER, MemberRole.MANAGER, MemberRole.STAFF);
    }

    @Test
    @DisplayName("운영자 · 소유자 역할 판정 — CUSTOMER 는 운영자가 아니고, 소유자는 ADMIN · OWNER 뿐이다")
    void operatorAndOwnerRoles() {
        assertThat(Arrays.stream(MemberRole.values()).filter(MemberRole::isOperator))
            .containsExactly(MemberRole.ADMIN, MemberRole.OWNER, MemberRole.MANAGER, MemberRole.STAFF);
        assertThat(Arrays.stream(MemberRole.values()).filter(MemberRole::isOwner)).containsExactly(MemberRole.ADMIN, MemberRole.OWNER);
        assertThat(MemberRole.operatorRoleNames()).containsExactly("ADMIN", "OWNER", "MANAGER", "STAFF");
    }

    private static Map<String, String> constants() throws IllegalAccessException {
        Map<String, String> constants = new HashMap<>();
        for (Field field : AdminAuthority.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers) && field.getType() == String.class) {
                constants.put(field.getName(), (String) field.get(null));
            }
        }
        return constants;
    }
}
