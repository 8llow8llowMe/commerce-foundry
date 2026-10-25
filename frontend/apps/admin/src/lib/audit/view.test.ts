import { describe, expect, it } from 'vitest';
import { auditLog, directory } from './test-fixtures';
import {
  actionLabel,
  describeAuditLog,
  formatAuditTime,
  resultLabel,
  targetText,
  UNKNOWN_ACTION,
  UNKNOWN_TARGET,
} from './view';

describe('actionLabel · resultLabel', () => {
  it('아는 행위 · 결과는 업무 이름이다', () => {
    expect(actionLabel('ADMIN_LOGIN')).toBe('관리자 로그인');
    expect(actionLabel('MEMBER_SEARCH')).toBe('회원 목록 조회');
    expect(resultLabel('SUCCESS')).toBe('성공');
    expect(resultLabel('FAILURE')).toBe('실패');
  });

  it('모르는 값은 원문을 보이지 않는다', () => {
    expect(actionLabel('JOB_CANCEL')).toBe(UNKNOWN_ACTION);
    expect(actionLabel('admin_login')).toBe(UNKNOWN_ACTION);
    expect(actionLabel('')).toBe(UNKNOWN_ACTION);
    expect(resultLabel('PARTIAL')).toBe('알 수 없음');
  });
});

describe('targetText', () => {
  const admins = directory();

  it('회원 목록 조회 조건을 문장으로 — 역할은 국문, 쪽은 1부터', () => {
    expect(targetText('members?role=ALL&page=0&size=20', 'MEMBER_SEARCH', admins)).toBe(
      '역할: 전체 · 1쪽',
    );
    expect(targetText('members?role=CUSTOMER&page=1&size=20', 'MEMBER_SEARCH', null)).toBe(
      '역할: 고객 · 2쪽',
    );
    expect(targetText('members?role=ADMIN&page=0&size=100', 'MEMBER_SEARCH', null)).toBe(
      '역할: 관리자 · 1쪽',
    );
  });

  it('관리자 로그인의 대상 계정은 이름표의 이름, 없으면 "관리자 계정" — 아이디는 보이지 않는다', () => {
    expect(targetText('member:101', 'ADMIN_LOGIN', admins)).toBe('관리자 계정: 김*수');
    expect(targetText('member:555', 'ADMIN_LOGIN', admins)).toBe('관리자 계정');
    expect(targetText('member:101', 'ADMIN_LOGIN', null)).toBe('관리자 계정');
    expect(targetText('member:101', 'SOMETHING_NEW', admins)).toBe('회원 계정: 김*수');
    expect(targetText('member:555', 'SOMETHING_NEW', null)).toBe('회원 계정');
  });

  it('대상이 없으면 대시다', () => {
    expect(targetText(null, 'ADMIN_LOGIN', admins)).toBe('—');
    expect(targetText('', 'ADMIN_LOGIN', admins)).toBe('—');
  });

  it('모르는 형식 · 값은 "기타" 다 — 원문을 흘리지 않는다', () => {
    const unknown = [
      'job:42',
      'member:',
      'member:0',
      'member:abc',
      'member:12 ',
      'members?role=VIP&page=0&size=20',
      'members?role=ALL&page=-1&size=20',
      'members?role=ALL&page=0',
      'members?role=ALL&page=0&size=20&keyword=hong',
      'members?role=ALL&role=ADMIN&page=0&size=20',
      'members?role=toString&page=0&size=20',
      'products?page=0',
    ];
    for (const target of unknown) {
      expect(targetText(target, 'MEMBER_SEARCH', admins), target).toBe(UNKNOWN_TARGET);
    }
  });
});

describe('describeAuditLog', () => {
  const admins = directory();

  it('관리자는 이름표의 마스킹한 이름이다', () => {
    expect(describeAuditLog(auditLog(), admins)).toEqual({
      action: '회원 목록 조회',
      result: '성공',
      failed: false,
      actor: '김*수',
      actorHint: null,
      target: '역할: 전체 · 1쪽',
    });
  });

  it('이름표에 없거나 못 읽었으면 "관리자" 로 두고 설명을 붙인다', () => {
    for (const view of [
      describeAuditLog(auditLog({ actorMemberId: '555' }), admins),
      describeAuditLog(auditLog(), null),
    ]) {
      expect(view.actor).toBe('관리자');
      expect(view.actorHint).toBe('관리자 이름을 확인할 수 없습니다.');
    }
  });

  it('로그인 실패는 행위자를 모른다 — 대상은 노린 관리자 계정이다', () => {
    const view = describeAuditLog(
      auditLog({
        action: 'ADMIN_LOGIN',
        actorMemberId: null,
        target: 'member:102',
        result: 'FAILURE',
      }),
      admins,
    );
    expect(view).toMatchObject({
      action: '관리자 로그인',
      result: '실패',
      failed: true,
      actor: '확인 안 됨',
      actorHint: '로그인에 실패해 누가 시도했는지 알 수 없습니다.',
      target: '관리자 계정: 이*',
    });
  });

  it('모르는 행위도 깨지지 않는다', () => {
    const view = describeAuditLog(
      auditLog({ action: 'JOB_CANCEL', actorMemberId: null, target: 'job:7', result: 'X' }),
      admins,
    );
    expect(view).toMatchObject({
      action: UNKNOWN_ACTION,
      result: '알 수 없음',
      failed: false,
      actor: '확인 안 됨',
      actorHint: '행위한 관리자가 기록되지 않았습니다.',
      target: UNKNOWN_TARGET,
    });
  });
});

describe('formatAuditTime', () => {
  it('한국 시각으로 보이고, 읽을 수 없으면 대시다', () => {
    expect(formatAuditTime('2026-10-24T03:00:00Z')).toContain('12:00:00');
    expect(formatAuditTime('not-a-date')).toBe('—');
  });
});
