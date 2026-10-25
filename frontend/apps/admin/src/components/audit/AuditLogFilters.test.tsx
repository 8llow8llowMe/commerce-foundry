import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { DEFAULT_QUERY } from '@/lib/audit/query';
import { AuditLogFilters } from './AuditLogFilters';

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));

describe('AuditLogFilters', () => {
  it('행위를 업무 이름으로 고른다 — 관리자 · 대상 조건은 없다', () => {
    const html = renderToStaticMarkup(
      <AuditLogFilters query={{ ...DEFAULT_QUERY, action: 'ADMIN_LOGIN' }} />,
    );
    expect(html).toContain('<option value="ADMIN_LOGIN" selected="">관리자 로그인</option>');
    expect(html).toContain('>회원 목록 조회</option>');
    expect(html.match(/<select/g)).toHaveLength(2);
    expect(html).not.toContain('대상');
    expect(html).toContain('조건 초기화');
  });

  it('결과를 전체 · 성공 · 실패로 고른다 — 코드는 보이지 않는다', () => {
    const html = renderToStaticMarkup(
      <AuditLogFilters query={{ ...DEFAULT_QUERY, result: 'FAILURE' }} />,
    );
    expect(html).toContain('>결과</label>');
    expect(html).toContain('<option value="SUCCESS">성공</option>');
    expect(html).toContain('<option value="FAILURE" selected="">실패</option>');
    expect(html).not.toContain('>FAILURE<');
    expect(html).toContain('조건 초기화');
  });

  it('조건이 없으면 두 칸 모두 전체이고 초기화 링크가 없다', () => {
    const html = renderToStaticMarkup(<AuditLogFilters query={DEFAULT_QUERY} />);
    expect(html.match(/<option value="" selected="">전체<\/option>/g)).toHaveLength(2);
    expect(html).not.toContain('조건 초기화');
  });
});
