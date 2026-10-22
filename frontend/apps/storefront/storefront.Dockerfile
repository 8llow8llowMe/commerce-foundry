# syntax=docker/dockerfile:1
# storefront 배포 이미지 — 빌드 agent 가 만든 standalone 번들만 담는다. 이미지 안에서 빌드하지 않는다 (docs/deploy.md).
#
# 빌드 컨텍스트: frontend/  (허용 경로는 같은 디렉터리의 storefront.Dockerfile.dockerignore)
# 선행 조건: pnpm --filter @cf/storefront build 완료, apps/storefront/public 디렉터리 존재(없으면 mkdir -p — COPY 는 없는 경로를 거른다)
#
# standalone 의 node_modules 에는 sharp 같은 네이티브 바이너리가 빌드 머신의 OS · CPU 로 들어간다.
# 그래서 런타임 베이스는 빌드 agent 와 같은 glibc(Debian) 계열이고, 빌드 agent 와 배포 호스트의 CPU 아키텍처가 같아야 한다.
ARG NODE_IMAGE=node:24-bookworm-slim
FROM ${NODE_IMAGE}

ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    HOSTNAME=0.0.0.0 \
    PORT=3000

WORKDIR /app

# 코드는 root 소유(읽기 전용)로 두고, 런타임이 쓰는 .next/cache 만 실행 사용자에게 연다.
COPY apps/storefront/.next/standalone ./
COPY apps/storefront/.next/static ./apps/storefront/.next/static
COPY apps/storefront/public ./apps/storefront/public
RUN mkdir -p apps/storefront/.next/cache && chown node:node apps/storefront/.next/cache

# 공식 node 이미지의 비루트 사용자(uid 1000)
USER node
EXPOSE 3000
CMD ["node", "apps/storefront/server.js"]
