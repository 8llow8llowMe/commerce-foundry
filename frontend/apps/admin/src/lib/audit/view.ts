import { roleLabel } from '@/lib/member/query';
import {
  AUDIT_ACTION_LABELS,
  AUDIT_ACTIONS,
  AUDIT_RESULT_LABELS,
  AUDIT_RESULTS,
  type AuditLogItem,
} from './types';

/**
 * 감사 로그 한 행 → 운영자 문구(#305). 행위 · 결과 코드, 대상 원문, 회원 아이디는 화면에 그대로 내지 않는다.
 * 모르는 행위 · 결과 · 대상 형식은 "기타" · "알 수 없음" 으로 보인다(원문을 흘리지 않는다).
 * 관리자 · 대상 계정은 이름 없이 "관리자" · "관리자 계정" 이다 — 이름(마스킹)은 BE #322 가 응답에 표시 이름 칸을 더한 뒤 붙인다.
 */
export type AuditLogView = {
  action: string;
  result: string;
  failed: boolean;
  actor: string;
  /** 행위자 칸 보충 설명(title) */
  actorHint: string | null;
  target: string;
};

export const UNKNOWN_ACTION = '기타';
export const UNKNOWN_TARGET = '기타';

export function actionLabel(action: string): string {
  const known = AUDIT_ACTIONS.find((item) => item === action);
  return known ? AUDIT_ACTION_LABELS[known] : UNKNOWN_ACTION;
}

export function resultLabel(result: string): string {
  const known = AUDIT_RESULTS.find((item) => item === result);
  return known ? AUDIT_RESULT_LABELS[known] : '알 수 없음';
}

function actorText(item: AuditLogItem): Pick<AuditLogView, 'actor' | 'actorHint'> {
  if (item.actorMemberId === null) {
    return {
      actor: '확인 안 됨',
      actorHint:
        item.action === 'ADMIN_LOGIN'
          ? '로그인에 실패해 누가 시도했는지 알 수 없습니다.'
          : '행위한 관리자가 기록되지 않았습니다.',
    };
  }
  return { actor: '관리자', actorHint: null };
}

const MEMBER_TARGET = /^member:[1-9]\d{0,18}$/;
const MEMBER_SEARCH_KEYS = ['role', 'page', 'size'];
const SEARCH_ROLES: Record<string, string> = {
  ALL: '전체',
  CUSTOMER: roleLabel('CUSTOMER'),
  ADMIN: roleLabel('ADMIN'),
};

/**
 * 대상 원문 → 문장. 형식은 BE `AdminAuditRecord` 가 만든다.
 * - `member:<id>` — 관리자 로그인의 대상 계정(성공은 자기 계정, 실패는 노린 관리자 계정). 아이디는 보이지 않는다.
 * - `members?role=<ALL|CUSTOMER|ADMIN>&page=<0부터>&size=<n>` — 회원 목록 조회 조건. 쪽은 1부터 보인다.
 * 그 밖의 형식 · 값(모르는 역할 · 남는 칸 등)은 원문을 보이지 않고 "기타" 다.
 */
export function targetText(target: string | null, action: string): string {
  if (target === null || target === '') return '—';

  if (MEMBER_TARGET.test(target)) return action === 'ADMIN_LOGIN' ? '관리자 계정' : '회원 계정';

  if (target.startsWith('members?')) {
    const params = new URLSearchParams(target.slice('members?'.length));
    const keys = [...params.keys()];
    const role = params.get('role');
    const page = params.get('page');
    const size = params.get('size');
    const exact =
      keys.length === MEMBER_SEARCH_KEYS.length &&
      MEMBER_SEARCH_KEYS.every((key) => params.getAll(key).length === 1);
    if (
      exact &&
      role !== null &&
      Object.hasOwn(SEARCH_ROLES, role) &&
      page !== null &&
      /^\d{1,9}$/.test(page) &&
      size !== null &&
      /^\d{1,3}$/.test(size)
    ) {
      return `역할: ${SEARCH_ROLES[role]} · ${Number(page) + 1}쪽`;
    }
  }

  return UNKNOWN_TARGET;
}

export function describeAuditLog(item: AuditLogItem): AuditLogView {
  return {
    action: actionLabel(item.action),
    result: resultLabel(item.result),
    failed: item.result === 'FAILURE',
    ...actorText(item),
    target: targetText(item.target, item.action),
  };
}

const dateTime = new Intl.DateTimeFormat('ko-KR', {
  dateStyle: 'short',
  timeStyle: 'medium',
  timeZone: 'Asia/Seoul',
});

/** 기록 시각(UTC ISO) → 한국 시각. 읽을 수 없으면 대시 */
export function formatAuditTime(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '—' : dateTime.format(date);
}
