import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { DEFAULT_QUERY } from '@/lib/audit/query';
import { AuditLogFilters } from './AuditLogFilters';

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));

describe('AuditLogFilters', () => {
  it('행위를 업무 이름으로 고른다 — 관리자 조건은 없다', () => {
    const html = renderToStaticMarkup(
      <AuditLogFilters query={{ action: 'ADMIN_LOGIN', page: 0 }} />,
    );
    expect(html).toContain('<option value="ADMIN_LOGIN" selected="">관리자 로그인</option>');
    expect(html).toContain('>회원 목록 조회</option>');
    expect(html.match(/<select/g)).toHaveLength(1);
    expect(html).toContain('조건 초기화');
  });

  it('조건이 없으면 초기화 링크가 없다', () => {
    const html = renderToStaticMarkup(<AuditLogFilters query={DEFAULT_QUERY} />);
    expect(html).not.toContain('조건 초기화');
  });
});
