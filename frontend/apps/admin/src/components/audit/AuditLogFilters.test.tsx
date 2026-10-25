import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { DEFAULT_QUERY } from '@/lib/audit/query';
import { directory } from '@/lib/audit/test-fixtures';
import { AuditLogFilters } from './AuditLogFilters';

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));

describe('AuditLogFilters', () => {
  it('행위는 업무 이름, 관리자는 마스킹한 이름 · 이메일로 고른다', () => {
    const html = renderToStaticMarkup(
      <AuditLogFilters
        query={{ action: 'ADMIN_LOGIN', actorMemberId: '101', page: 0 }}
        adminsState={{ kind: 'done', admins: directory() }}
      />,
    );
    expect(html).toContain('<option value="ADMIN_LOGIN" selected="">관리자 로그인</option>');
    expect(html).toContain('>회원 목록 조회</option>');
    expect(html).toContain('<option value="101" selected="">김*수 (ad***@e***.com)</option>');
    expect(html).toContain('이*');
    expect(html).not.toContain('선택한 관리자');
    expect(html).toContain('조건 초기화');
  });

  it('주소의 관리자가 이름표에 없으면 "선택한 관리자" 로 남긴다', () => {
    const html = renderToStaticMarkup(
      <AuditLogFilters
        query={{ ...DEFAULT_QUERY, actorMemberId: '555' }}
        adminsState={{ kind: 'done', admins: null }}
      />,
    );
    expect(html).toContain('<option value="555" selected="">선택한 관리자</option>');
    expect(html).toContain('관리자 목록을 불러오지 못해 관리자로 거를 수 없습니다.');
  });

  it('이름표를 읽는 동안은 관리자 칸을 막는다', () => {
    const html = renderToStaticMarkup(
      <AuditLogFilters query={DEFAULT_QUERY} adminsState={{ kind: 'loading' }} />,
    );
    expect(html).toMatch(/<select[^>]*disabled=""/);
    expect(html).toContain('관리자 목록을 불러오는 중입니다.');
    expect(html).not.toContain('조건 초기화');
  });

  it('100명을 넘으면 앞의 100명만 고를 수 있다고 알린다', () => {
    const html = renderToStaticMarkup(
      <AuditLogFilters
        query={DEFAULT_QUERY}
        adminsState={{ kind: 'done', admins: directory(undefined, false) }}
      />,
    );
    expect(html).toContain('앞의 100명만');
  });
});
