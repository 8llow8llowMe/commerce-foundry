import { defineConfig } from 'vitest/config';

// 테스트는 루트에서 한 번에 돈다. 패키지별 설정을 두지 않는다.
export default defineConfig({
  oxc: { jsx: { runtime: 'automatic' } },
  test: {
    include: ['{apps,packages}/*/src/**/*.test.{ts,tsx}'],
    environment: 'node',
  },
});
