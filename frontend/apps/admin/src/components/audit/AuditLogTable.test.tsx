import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { auditLog, directory } from '@/lib/audit/test-fixtures';
import { AuditLogTable } from './AuditLogTable';

describe('AuditLogTable', () => {
  it('운영자 문구만 보인다 — 코드 · 대상 원문 · 아이디를 내지 않는다', () => {
    const html = renderToStaticMarkup(
      <AuditLogTable
        admins={directory()}
        logs={[
          auditLog({ auditLogId: '900', target: 'members?role=CUSTOMER&page=1&size=20' }),
          auditLog({
            auditLogId: '901',
            action: 'ADMIN_LOGIN',
            actorMemberId: null,
            target: 'member:102',
            result: 'FAILURE',
          }),
        ]}
      />,
    );
    expect(html).toContain('회원 목록 조회');
    expect(html).toContain('역할: 고객 · 2쪽');
    expect(html).toContain('김*수');
    expect(html).toContain('관리자 로그인');
    expect(html).toContain('관리자 계정: 이*');
    expect(html).toContain('확인 안 됨');
    expect(html).toContain('title="로그인에 실패해 누가 시도했는지 알 수 없습니다."');
    expect(html).toContain('>성공<');
    expect(html).toContain('>실패<');
    expect(html).toContain('text-danger');

    for (const raw of [
      'MEMBER_SEARCH',
      'ADMIN_LOGIN',
      'SUCCESS',
      'FAILURE',
      'members?',
      'member:',
      '101',
      '102',
      '900',
      '901',
    ]) {
      expect(html, raw).not.toContain(raw);
    }
  });

  it('이름표가 없으면 "관리자" 로 보인다', () => {
    const html = renderToStaticMarkup(<AuditLogTable admins={null} logs={[auditLog()]} />);
    expect(html).toContain('title="관리자 이름을 확인할 수 없습니다."');
    expect(html).toContain('>관리자<');
  });
});
