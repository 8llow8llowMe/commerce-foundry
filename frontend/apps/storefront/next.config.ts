import type { NextConfig } from 'next';

const nextConfig: NextConfig = {
  // 워크스페이스 패키지는 빌드하지 않고 TS 소스를 내보낸다.
  transpilePackages: ['@cf/api-client', '@cf/ui', '@cf/brand'],
  poweredByHeader: false,
};

export default nextConfig;
