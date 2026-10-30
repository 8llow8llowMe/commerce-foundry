import path from 'node:path';
import type { NextConfig } from 'next';

const nextConfig: NextConfig = {
  // 워크스페이스 패키지는 빌드하지 않고 TS 소스를 내보낸다.
  transpilePackages: ['@cf/api-client', '@cf/ui', '@cf/sanitize', '@cf/brand'],
  poweredByHeader: false,
  // 배포 이미지는 standalone 번들(server.js + 추적된 node_modules)만 담는다 (docs/deploy.md).
  output: 'standalone',
  // 추적 루트를 모노레포 루트(frontend/)로 고정한다. 번들 안 경로가 apps/<app>/server.js 가 된다.
  outputFileTracingRoot: path.join(import.meta.dirname, '../..'),
};

export default nextConfig;
