import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { auditLog } from '@/lib/audit/test-fixtures';
import { AuditLogTable } from './AuditLogTable';

describe('AuditLogTable', () => {
  it('운영자 문구만 보인다 — 코드 · 대상 원문 · 아이디를 내지 않는다', () => {
    const html = renderToStaticMarkup(
      <AuditLogTable
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
    expect(html).toContain('>관리자</span>');
    expect(html).toContain('관리자 로그인');
    expect(html).toContain('관리자 계정');
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

  it('마스킹 이름이 오면 관리자 · 대상 계정에 이름을 보인다 — 아이디는 내지 않는다', () => {
    const html = renderToStaticMarkup(
      <AuditLogTable
        logs={[
          auditLog({ auditLogId: '900', actorDisplayName: '김*수' }),
          auditLog({
            auditLogId: '901',
            action: 'ADMIN_LOGIN',
            actorMemberId: null,
            target: 'member:102',
            targetDisplayName: '이*진',
            result: 'FAILURE',
          }),
        ]}
      />,
    );
    expect(html).toContain('>김*수<');
    expect(html).toContain('>이*진<');
    expect(html).toContain('확인 안 됨');
    expect(html).not.toContain('>관리자</span>');
    expect(html).not.toContain('관리자 계정');
    for (const raw of ['member:', '101', '102']) {
      expect(html, raw).not.toContain(raw);
    }
  });
});
