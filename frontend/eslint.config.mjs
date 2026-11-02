import { defineConfig, globalIgnores } from 'eslint/config';
import nextVitals from 'eslint-config-next/core-web-vitals';
import nextTs from 'eslint-config-next/typescript';
import prettier from 'eslint-config-prettier/flat';

// Tailwind 임의 색 값(bg-[#fff], text-[rgb(...)] 등)을 막는다. 색은 테마 토큰 클래스로만 쓴다.
const ARBITRARY_COLOR =
  '/\\b(bg|text|border|ring|outline|fill|stroke|from|via|to|decoration|shadow)-\\[(#|rgb|hsl|oklch)/';

const COLOR_SYNTAX = [
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
];

// 샘플 주문 업무 연습(#365)은 실서비스를 부르지 않는다 — 고정 fixture 와 연습 transport 만 쓴다(frontend/docs/architecture.md)
const PRACTICE_ONLY =
  '연습 화면은 실서비스를 부르지 않는다 — lib/practice 의 fixture · transport 만 쓴다(#365)';

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
      'no-restricted-syntax': ['error', ...COLOR_SYNTAX],
    },
  },
  {
    files: [
      'apps/admin/src/lib/practice/**/*.{ts,tsx}',
      'apps/admin/src/components/practice/**/*.{ts,tsx}',
      'apps/admin/src/app/*/practice/**/*.{ts,tsx}',
    ],
    ignores: ['**/*.test.{ts,tsx}'],
    rules: {
      'no-restricted-globals': [
        'error',
        ...[
          'fetch',
          'XMLHttpRequest',
          'EventSource',
          'WebSocket',
          'navigator',
          'window',
          'self',
          'globalThis',
        ].map((name) => ({ name, message: PRACTICE_ONLY })),
      ],
      'no-restricted-imports': [
        'error',
        {
          paths: [
            {
              name: '@cf/api-client',
              allowImportNames: ['formatMoney', 'Money'],
              message: PRACTICE_ONLY,
            },
          ],
          patterns: [
            // 앱 모듈은 연습 폴더 것만 — 다른 화면 모듈은 안에서 apiFetch 를 부를 수 있다
            { regex: '^@/(?!lib/practice/|components/practice/)', message: PRACTICE_ONLY },
            // 상대 경로로 연습 폴더 밖을 들여오지 못하게 — 연습 폴더끼리는 @/ 로 쓴다
            { regex: '^\\.\\./', message: PRACTICE_ONLY },
            {
              group: ['@cf/api-client/*', 'next/headers', 'next/navigation'],
              message: PRACTICE_ONLY,
            },
          ],
        },
      ],
      // 동적 import 로 위 제한을 돌아가지 못하게 한다
      'no-restricted-syntax': [
        'error',
        ...COLOR_SYNTAX,
        { selector: 'ImportExpression', message: PRACTICE_ONLY },
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
