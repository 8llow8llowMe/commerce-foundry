# delivery-service 배포 이미지 (backend/docs/deploy-guide.md).
#
# 싱글 스테이지다. 이미지 안에서 Gradle 을 돌리지 않는다 — Jenkins 빌드 agent 가 `./gradlew :service:delivery-service:bootJar` 로 만든 jar 를
# 배포 agent 가 이 디렉터리에 app.jar 로 놓고 `docker compose up -d --build` 로 배포 호스트에서 이미지를 만든다.
#
# 베이스: Eclipse Temurin 21 JRE (Ubuntu 24.04). 같은 태그가 amd64 · arm64 를 함께 내는 멀티 아키텍처 이미지라 배포 호스트
# 아키텍처가 정해지지 않아도 그대로 쓴다. CI(setup-java temurin 21)와 같은 HotSpot 배포판이고, compose healthcheck 가 쓰는 curl 이 들어 있다.
# 태그는 정확한 버전으로 고정한다(latest · 21-jre 금지). 올릴 때는 7개 모듈 Dockerfile 을 같이 올린다.
FROM eclipse-temurin:21.0.12.1_1-jre-noble

# root 로 돌리지 않는다. 앱은 /tmp(Tomcat 임시 디렉터리) 말고는 쓰지 않는다
RUN groupadd --system app && useradd --system --gid app --no-create-home --shell /usr/sbin/nologin app

WORKDIR /app

ARG JAR_FILE=app.jar
COPY ${JAR_FILE} /app/delivery-service.jar

USER app

# 컨테이너 안 포트 — application.yml 의 server.port 와 같다. 호스트 포트는 compose 가 env 로 정한다
EXPOSE 8085

# - exec: java 가 PID 1 이 돼 docker stop 의 SIGTERM 을 받아 graceful shutdown 한다(sh 가 PID 1 이면 신호를 넘기지 않아 강제 종료된다)
# - 힙은 컨테이너 메모리 상한(compose deploy.resources.limits.memory)의 비율로 정한다. 나머지 30% 는 메타스페이스 · 스레드 스택 · 다이렉트 버퍼 몫이다
# - OOM 이면 프로세스를 끝내 restart 정책이 다시 띄우게 한다 — 반쯤 죽은 채 헬스만 UP 으로 남지 않게
# - 시간대: 업무 시각은 Clock.systemUTC() · Instant 라 JVM 시간대와 무관하다. 로그 시각만 바뀐다. 기본 UTC
# - 프로파일은 SPRING_PROFILES_ACTIVE 환경변수를 Spring 이 직접 읽는다(compose 가 서비스마다 dev · prod 로 고정한다)
ENTRYPOINT ["sh", "-c", "exec java -Duser.timezone=\"${TIME_ZONE:-UTC}\" -XX:MaxRAMPercentage=70.0 -XX:InitialRAMPercentage=30.0 -XX:+ExitOnOutOfMemoryError -jar /app/delivery-service.jar"]
