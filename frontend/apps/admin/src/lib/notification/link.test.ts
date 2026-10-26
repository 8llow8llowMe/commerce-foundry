import { describe, expect, it } from 'vitest';
import { consolePathOf } from './link';

describe('consolePathOf', () => {
  it.each([
    ['/admin/jobs/42', '/jobs/42'],
    ['/admin', '/'],
    ['/admin/', '/'],
    ['/admin?x=1', '/?x=1'],
    ['/stocks/7', '/stocks/7'],
    ['/administrators', '/administrators'],
    ['/admin/jobs/../stocks/7', '/stocks/7'],
  ])('%s → %s', (link, path) => {
    expect(consolePathOf(link)).toBe(path);
  });

  it.each([
    null,
    '',
    'https://evil.test/x',
    '//evil.test',
    '/admin//evil.test',
    '/admin/\\evil',
    '/login',
    '/admin/api/v1/x',
    'jobs/1',
    '/admin/../api/v1/x',
    '/admin/./../login',
    '/admin/%2e%2e/api/v1/x',
  ])('쓸 수 없는 링크 %j 는 null', (link) => {
    expect(consolePathOf(link)).toBeNull();
  });
});
