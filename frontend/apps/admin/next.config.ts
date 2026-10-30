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
  // 콘솔은 어떤 사이트의 iframe 에도 열리지 않는다(클릭재킹, #345). proxy 가 건너뛰는 /login · /api 까지 덮으려고 여기 둔다.
  // X-Frame-Options 는 frame-ancestors 를 모르는 구형 브라우저용이다
  async headers() {
    return [
      {
        source: '/:path*',
        headers: [
          { key: 'Content-Security-Policy', value: "frame-ancestors 'none'" },
          { key: 'X-Frame-Options', value: 'DENY' },
        ],
      },
    ];
  },
};

export default nextConfig;
