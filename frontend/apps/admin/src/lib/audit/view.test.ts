import { describe, expect, it } from 'vitest';
import { auditLog } from './test-fixtures';
import { AUDIT_ACTION_GROUPS, AUDIT_ACTION_LABELS, AUDIT_ACTIONS } from './types';
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

  it('운영자 계정 · OTP · 비밀번호 행위도 업무 이름이다(#712)', () => {
    expect(actionLabel('ADMIN_CREATE')).toBe('운영자 추가');
    expect(actionLabel('ADMIN_DEACTIVATE')).toBe('운영자 비활성화');
    expect(actionLabel('ADMIN_REACTIVATE')).toBe('운영자 다시 켜기');
    expect(actionLabel('ADMIN_ROLE_CHANGE')).toBe('운영자 역할 변경');
    expect(actionLabel('ADMIN_SEARCH')).toBe('운영자 목록 조회');
    expect(actionLabel('ADMIN_PASSWORD_CHANGE')).toBe('비밀번호 변경');
    expect(actionLabel('ADMIN_PASSWORD_RESET_LINK_SEND')).toBe('비밀번호 재설정 링크 발송');
    expect(actionLabel('ADMIN_PASSWORD_RESET_USE')).toBe('비밀번호 재설정 링크 사용');
    expect(actionLabel('ADMIN_LOGIN_PASSWORD_VERIFIED')).toBe('로그인 1단계(비밀번호) 통과');
    expect(actionLabel('ADMIN_OTP_ENROLL')).toBe('OTP 등록');
    expect(actionLabel('ADMIN_OTP_DISABLE')).toBe('OTP 해제');
    expect(actionLabel('ADMIN_OTP_RECOVERY_REGENERATE')).toBe('OTP 복구 코드 재발급');
    expect(actionLabel('ADMIN_OTP_PEER_RECOVERY')).toBe('동료 OTP 복구');
  });

  it('서버 15종이 모두 이름이 있고 묶음에 정확히 한 번씩 든다', () => {
    expect(AUDIT_ACTIONS).toHaveLength(15);
    for (const action of AUDIT_ACTIONS) {
      expect(actionLabel(action), action).toBe(AUDIT_ACTION_LABELS[action]);
      expect(actionLabel(action), action).not.toBe(UNKNOWN_ACTION);
    }
    expect(AUDIT_ACTION_GROUPS.flatMap((group) => group.actions)).toEqual([...AUDIT_ACTIONS]);
    expect(new Set(Object.values(AUDIT_ACTION_LABELS)).size).toBe(AUDIT_ACTIONS.length);
  });

  it('모르는 값은 원문을 보이지 않는다', () => {
    expect(actionLabel('JOB_CANCEL')).toBe(UNKNOWN_ACTION);
    expect(actionLabel('admin_login')).toBe(UNKNOWN_ACTION);
    expect(actionLabel('')).toBe(UNKNOWN_ACTION);
    expect(resultLabel('PARTIAL')).toBe('알 수 없음');
  });
});

describe('targetText', () => {
  it('회원 목록 조회 조건을 문장으로 — 역할은 국문, 쪽은 1부터', () => {
    expect(targetText('members?role=ALL&page=0&size=20', 'MEMBER_SEARCH')).toBe('역할: 전체 · 1쪽');
    expect(targetText('members?role=CUSTOMER&page=1&size=20', 'MEMBER_SEARCH')).toBe(
      '역할: 고객 · 2쪽',
    );
    expect(targetText('members?role=ADMIN&page=0&size=100', 'MEMBER_SEARCH')).toBe(
      '역할: 관리자 · 1쪽',
    );
  });

  it('운영자 목록 조회 조건을 문장으로 — 상태는 국문, 쪽은 1부터(#712)', () => {
    expect(targetText('admins?active=ALL&page=0&size=20', 'ADMIN_SEARCH')).toBe('상태: 전체 · 1쪽');
    expect(targetText('admins?active=true&page=2&size=20', 'ADMIN_SEARCH')).toBe(
      '상태: 사용 중 · 3쪽',
    );
    expect(targetText('admins?active=false&page=0&size=100', 'ADMIN_SEARCH')).toBe(
      '상태: 사용 안 함 · 1쪽',
    );
  });

  it('관리자 로그인의 대상 계정은 "관리자 계정" 이다 — 아이디는 보이지 않는다', () => {
    expect(targetText('member:101', 'ADMIN_LOGIN')).toBe('관리자 계정');
    expect(targetText('member:4611686018427387904', 'ADMIN_LOGIN')).toBe('관리자 계정');
    expect(targetText('member:101', 'SOMETHING_NEW')).toBe('회원 계정');
  });

  it('운영자 행위의 member 대상은 모두 "관리자 계정" 이다(#712)', () => {
    for (const action of AUDIT_ACTIONS.filter((item) => item !== 'MEMBER_SEARCH')) {
      expect(targetText('member:101', action), action).toBe('관리자 계정');
      expect(targetText('member:101', action, '김*수'), action).toBe('김*수');
    }
    expect(targetText('member:101', 'MEMBER_SEARCH')).toBe('회원 계정');
  });

  it('member 대상에 마스킹 이름이 있으면 이름을 보인다 — 다른 형식에는 쓰지 않는다', () => {
    expect(targetText('member:101', 'ADMIN_LOGIN', '김*수')).toBe('김*수');
    expect(targetText('member:101', 'ADMIN_LOGIN', ' ')).toBe('관리자 계정');
    expect(targetText('member:101', 'SOMETHING_NEW', null)).toBe('회원 계정');
    expect(targetText('members?role=ALL&page=0&size=20', 'MEMBER_SEARCH', '김*수')).toBe(
      '역할: 전체 · 1쪽',
    );
    expect(targetText('job:7', 'SOMETHING_NEW', '김*수')).toBe(UNKNOWN_TARGET);
  });

  it('대상이 없으면 대시다', () => {
    expect(targetText(null, 'ADMIN_LOGIN')).toBe('—');
    expect(targetText('', 'ADMIN_LOGIN')).toBe('—');
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
      'members?active=ALL&page=0&size=20',
      'admins?active=TRUE&page=0&size=20',
      'admins?active=yes&page=0&size=20',
      'admins?active=ALL&page=0',
      'admins?active=ALL&page=x&size=20',
      'admins?active=ALL&page=0&size=20&role=OWNER',
      'admins?active=true&active=false&page=0&size=20',
      'admins?role=ALL&page=0&size=20',
      'admins?active=hasOwnProperty&page=0&size=20',
      'products?page=0',
    ];
    for (const target of unknown) {
      expect(targetText(target, 'MEMBER_SEARCH'), target).toBe(UNKNOWN_TARGET);
    }
  });
});

describe('describeAuditLog', () => {
  it('관리자 이름이 없으면 "관리자" 다', () => {
    expect(describeAuditLog(auditLog())).toEqual({
      action: '회원 목록 조회',
      result: '성공',
      failed: false,
      actor: '관리자',
      actorHint: null,
      target: '역할: 전체 · 1쪽',
    });
  });

  it('관리자 · 대상 계정의 마스킹 이름이 있으면 이름을 보인다', () => {
    const view = describeAuditLog(
      auditLog({
        action: 'ADMIN_LOGIN',
        actorDisplayName: '김*수',
        target: 'member:101',
        targetDisplayName: '김*수',
      }),
    );
    expect(view).toMatchObject({ actor: '김*수', actorHint: null, target: '김*수' });
  });

  it('로그인 실패는 행위자를 모른다 — 대상은 노린 관리자 계정이다', () => {
    const view = describeAuditLog(
      auditLog({
        action: 'ADMIN_LOGIN',
        actorMemberId: null,
        target: 'member:102',
        result: 'FAILURE',
      }),
    );
    expect(view).toMatchObject({
      action: '관리자 로그인',
      result: '실패',
      failed: true,
      actor: '확인 안 됨',
      actorHint: '로그인에 실패해 누가 시도했는지 알 수 없습니다.',
      target: '관리자 계정',
    });
  });

  it('로그인 1단계 통과는 인증 전 기록이다 — 실패가 아니라 원래 행위자가 없다(#712)', () => {
    const view = describeAuditLog(
      auditLog({
        action: 'ADMIN_LOGIN_PASSWORD_VERIFIED',
        actorMemberId: null,
        target: 'member:101',
      }),
    );
    expect(view).toMatchObject({
      action: '로그인 1단계(비밀번호) 통과',
      result: '성공',
      actor: '인증 전',
      target: '관리자 계정',
    });
    expect(view.actorHint).toContain('행위자가 없습니다');
  });

  it('재설정 링크 사용도 인증 전 기록이고, 로그인 실패와 문구가 다르다(#712)', () => {
    const used = describeAuditLog(
      auditLog({
        action: 'ADMIN_PASSWORD_RESET_USE',
        actorMemberId: null,
        target: 'member:101',
        targetDisplayName: '김*수',
        result: 'FAILURE',
      }),
    );
    expect(used).toMatchObject({
      action: '비밀번호 재설정 링크 사용',
      failed: true,
      actor: '인증 전',
      target: '김*수',
    });
    expect(used.actorHint).toContain('재설정 링크');

    const sent = describeAuditLog(
      auditLog({
        action: 'ADMIN_PASSWORD_RESET_LINK_SEND',
        actorDisplayName: '이*희',
        target: 'member:102',
      }),
    );
    expect(sent).toMatchObject({ actor: '이*희', actorHint: null, target: '관리자 계정' });
  });

  it('운영자 추가가 계정을 만들기 전에 거절되면 대상이 없다', () => {
    const view = describeAuditLog(
      auditLog({ action: 'ADMIN_CREATE', target: null, result: 'FAILURE' }),
    );
    expect(view).toMatchObject({ action: '운영자 추가', target: '—', actor: '관리자' });
  });

  it('모르는 행위도 깨지지 않는다', () => {
    const view = describeAuditLog(
      auditLog({ action: 'JOB_CANCEL', actorMemberId: null, target: 'job:7', result: 'X' }),
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
