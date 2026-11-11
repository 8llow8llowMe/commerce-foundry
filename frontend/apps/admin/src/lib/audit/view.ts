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
 * 관리자 · 대상 계정은 서버가 준 마스킹 이름(#322)을 보이고, 이름이 없으면 "관리자" · "관리자 계정" · "회원 계정" 이다.
 * 행위자가 없는(null) 행은 로그인 실패("확인 안 됨")와 인증 전 기록("인증 전" — 로그인 1단계 통과 · 재설정 링크 사용)을 나눈다.
 * 아이디 원문은 이름이 없어도 보이지 않는다.
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

/** 서버가 마스킹한 표시 이름. 비었거나 공백뿐이면 없는 것으로 본다 */
export function displayNameOf(value: string | null | undefined): string | null {
  const name = value?.trim();
  return name ? name : null;
}

/** 행위자 없음(null)의 보충 설명. 인증 전 기록은 실패가 아니라 원래 행위자가 없다(BE `AdminAuditRecord`) */
const PRE_AUTH_HINTS: Record<string, string> = {
  ADMIN_LOGIN_PASSWORD_VERIFIED:
    '로그인 2단계(OTP) 전 기록이라 행위자가 없습니다. 대상이 비밀번호를 통과한 계정입니다.',
  ADMIN_PASSWORD_RESET_USE:
    '로그인 전에 재설정 링크로 바꾼 기록이라 행위자가 없습니다. 대상이 링크를 쓴 계정입니다.',
};

function actorText(item: AuditLogItem): Pick<AuditLogView, 'actor' | 'actorHint'> {
  if (item.actorMemberId === null) {
    const preAuthHint = Object.hasOwn(PRE_AUTH_HINTS, item.action)
      ? PRE_AUTH_HINTS[item.action]
      : undefined;
    if (preAuthHint) return { actor: '인증 전', actorHint: preAuthHint };
    return {
      actor: '확인 안 됨',
      actorHint:
        item.action === 'ADMIN_LOGIN'
          ? '로그인에 실패해 누가 시도했는지 알 수 없습니다.'
          : '행위한 관리자가 기록되지 않았습니다.',
    };
  }
  return { actor: displayNameOf(item.actorDisplayName) ?? '관리자', actorHint: null };
}

const MEMBER_TARGET = /^member:[1-9]\d{0,18}$/;

/** `member:<id>` 대상이 회원(고객 포함)이 아니라 운영자 계정인 행위 — MEMBER_SEARCH 를 뺀 전부다 */
function isAdminAccountAction(action: string): boolean {
  return action !== 'MEMBER_SEARCH' && AUDIT_ACTIONS.some((item) => item === action);
}

/**
 * `<prefix>?<key>=..&page=..&size=..` 조건 원문을 읽는다. 칸이 정확히 세 개 · 한 번씩이고,
 * 첫 칸 값이 허용 목록에 있고 쪽 · 크기가 숫자일 때만 `[이름, 쪽(1부터)]` 이다. 그 밖은 null.
 */
function searchCondition(
  target: string,
  prefix: string,
  key: string,
  labels: Record<string, string>,
): [string, number] | null {
  if (!target.startsWith(`${prefix}?`)) return null;
  const params = new URLSearchParams(target.slice(prefix.length + 1));
  const keys = [key, 'page', 'size'];
  const exact =
    [...params.keys()].length === keys.length &&
    keys.every((name) => params.getAll(name).length === 1);
  const raw = params.get(key);
  const label = raw !== null && Object.hasOwn(labels, raw) ? labels[raw] : undefined;
  const page = params.get('page');
  const size = params.get('size');
  if (
    !exact ||
    label === undefined ||
    page === null ||
    !/^\d{1,9}$/.test(page) ||
    size === null ||
    !/^\d{1,3}$/.test(size)
  ) {
    return null;
  }
  return [label, Number(page) + 1];
}

const SEARCH_ROLES: Record<string, string> = {
  ALL: '전체',
  CUSTOMER: roleLabel('CUSTOMER'),
  ADMIN: roleLabel('ADMIN'),
};

/** 운영자 목록 조회의 사용 여부 조건(BE `operatorsSearched` — 없으면 ALL, 그 밖은 소문자 true · false) */
const SEARCH_ACTIVE: Record<string, string> = {
  ALL: '전체',
  true: '사용 중',
  false: '사용 안 함',
};

/**
 * 대상 원문 → 문장. 형식은 BE `AdminAuditRecord` 가 만든다.
 * - `member:<id>` — 운영자 행위(로그인 · 운영자 계정 · OTP · 비밀번호)는 그 관리자 계정, 그 밖은 회원 계정이다. 아이디는 보이지 않는다.
 *   마스킹 이름(`displayName`, #322)이 있으면 그 이름, 없으면 "관리자 계정" · "회원 계정" 이다.
 * - `members?role=<ALL|CUSTOMER|ADMIN>&page=<0부터>&size=<n>` — 회원 목록 조회 조건. 쪽은 1부터 보인다.
 * - `admins?active=<ALL|true|false>&page=<0부터>&size=<n>` — 운영자 목록 조회 조건(#712).
 * 그 밖의 형식 · 값(모르는 역할 · 남는 칸 등)은 원문을 보이지 않고 "기타" 다.
 */
export function targetText(
  target: string | null,
  action: string,
  displayName: string | null = null,
): string {
  if (target === null || target === '') return '—';

  if (MEMBER_TARGET.test(target))
    return (
      displayNameOf(displayName) ?? (isAdminAccountAction(action) ? '관리자 계정' : '회원 계정')
    );

  const members = searchCondition(target, 'members', 'role', SEARCH_ROLES);
  if (members) return `역할: ${members[0]} · ${members[1]}쪽`;

  const admins = searchCondition(target, 'admins', 'active', SEARCH_ACTIVE);
  if (admins) return `상태: ${admins[0]} · ${admins[1]}쪽`;

  return UNKNOWN_TARGET;
}

export function describeAuditLog(item: AuditLogItem): AuditLogView {
  return {
    action: actionLabel(item.action),
    result: resultLabel(item.result),
    failed: item.result === 'FAILURE',
    ...actorText(item),
    target: targetText(item.target, item.action, item.targetDisplayName),
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
