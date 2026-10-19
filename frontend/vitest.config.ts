import path from 'node:path';
import { defineConfig, type Plugin } from 'vitest/config';

/**
 * 앱 내부 별칭 `@/` 를 가져오는 파일이 속한 앱의 src/ 로 푼다(각 앱 tsconfig 의 paths 와 같다).
 * 테스트를 루트에서 한 번에 돌리므로 앱마다 다른 `@/` 를 한 alias 로 둘 수 없다.
 */
function appAlias(): Plugin {
  const appsDir = path.resolve(import.meta.dirname, 'apps');
  return {
    name: 'cf-app-alias',
    enforce: 'pre',
    async resolveId(source, importer) {
      if (!source.startsWith('@/') || !importer) return null;
      const relative = path.relative(appsDir, importer);
      const app = relative.split(path.sep)[0];
      if (!app || relative.startsWith('..')) return null;
      const target = path.join(appsDir, app, 'src', source.slice(2));
      return this.resolve(target, importer, { skipSelf: true });
    },
  };
}

// 테스트는 루트에서 한 번에 돈다. 패키지별 설정을 두지 않는다.
export default defineConfig({
  plugins: [appAlias()],
  oxc: { jsx: { runtime: 'automatic' } },
  test: {
    include: ['{apps,packages}/*/src/**/*.test.{ts,tsx}'],
    environment: 'node',
  },
});
