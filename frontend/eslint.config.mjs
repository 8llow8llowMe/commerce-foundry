import { defineConfig, globalIgnores } from 'eslint/config';
import nextVitals from 'eslint-config-next/core-web-vitals';
import nextTs from 'eslint-config-next/typescript';
import prettier from 'eslint-config-prettier/flat';

// Tailwind 임의 색 값(bg-[#fff], text-[rgb(...)] 등)을 막는다. 색은 테마 토큰 클래스로만 쓴다.
const ARBITRARY_COLOR =
  '/\\b(bg|text|border|ring|outline|fill|stroke|from|via|to|decoration|shadow)-\\[(#|rgb|hsl|oklch)/';

export default defineConfig([
  ...nextVitals,
  ...nextTs,
  prettier,
  {
    // react 는 루트가 아니라 각 앱·패키지에 설치되므로 버전을 detect 하지 않고 고정한다.
    settings: { next: { rootDir: ['apps/*/'] }, react: { version: '19.3' } },
  },
  {
    files: ['**/*.{ts,tsx}'],
    rules: {
      'no-restricted-syntax': [
        'error',
        {
          selector: `Literal[value=${ARBITRARY_COLOR}]`,
          message:
            '임의 색 값 대신 테마 토큰 클래스(bg-primary 등)를 쓴다. frontend/docs/styling-theme.md',
        },
        {
          selector: `TemplateElement[value.raw=${ARBITRARY_COLOR}]`,
          message:
            '임의 색 값 대신 테마 토큰 클래스(bg-primary 등)를 쓴다. frontend/docs/styling-theme.md',
        },
      ],
    },
  },
  globalIgnores([
    '**/node_modules/**',
    '**/.next/**',
    '**/.turbo/**',
    '**/next-env.d.ts',
    'test-results/**',
    'coverage/**',
  ]),
]);
