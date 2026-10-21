package com.commercefoundry.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Testcontainers 테스트를 Docker 가 있거나 CI 일 때만 돌린다. 판정은 {@link DockerOrCiCondition}.
 *
 * <ul>
 *   <li>Docker 가 있으면 돈다</li>
 *   <li>Docker 가 없어도 환경변수 {@code CI=true}(GitHub Actions)면 돈다 — 컨테이너 기동 실패로 <b>시끄럽게 실패한다</b></li>
 *   <li>그 밖(Docker 없는 로컬)은 건너뛴다</li>
 * </ul>
 *
 * <p>{@code @Testcontainers(disabledWithoutDocker = true)} 를 쓰지 않는 이유 — CI 러너에서 Docker 를 못 찾으면 테스트가 조용히 skip 되고
 * 초록불이 난다. 그러면 실제 S3 호환 서버 왕복(서명 · Range · CopyObject · 조건부 요청) 검증이 소리 없이 사라진다.
 * {@code @Testcontainers}(기본값) 와 함께 붙인다 ({@code bootstrap-conventions.md} 리포지토리 테스트).
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ExtendWith(DockerOrCiCondition.class)
public @interface EnabledWithDockerOrOnCi {
}
