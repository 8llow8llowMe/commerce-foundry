import { ApiError, formatMoney, type Money } from '@cf/api-client';
import { accessDeniedOf, LOAD_FAILED, type AccessDenied } from '@/lib/api-errors';
import {
  DOMESTIC_POLICY_HISTORY_PATH,
  type DomesticPolicyHistoryItem,
  type DomesticPolicySnapshot,
} from './types';

/** 서버 상한(20, `DELIVERY_138`) — 이력 한 건이 구간을 최대 400행 펼친다 */
export const HISTORY_PAGE_SIZE = 20;

/** 교체한 사람의 이름이 없을 때 문구. 아이디 원문(`adminMemberId`)은 보이지 않는다(#305) */
export const ADMIN_LABEL = '관리자';

/** 교체한 사람 — 서버가 마스킹한 이름(`adminDisplayName`, #322), 없으면 "관리자" */
export function historyAdminLabel({ adminDisplayName }: DomesticPolicyHistoryItem): string {
  const name = adminDisplayName?.trim();
  return name ? name : ADMIN_LABEL;
}

/** 503 `DELIVERY_013` — 이력은 있는데 관리자 이름 조회(auth-service)가 실패했다 */
export const ADMIN_NAME_UNAVAILABLE_CODE = 'DELIVERY_013';
export const ADMIN_NAME_FAILED = '관리자 이름을 불러오지 못했습니다. 잠시 후 다시 불러와 주세요.';

export const historyPagePath = (page: number) =>
  `${DOMESTIC_POLICY_HISTORY_PATH}?page=${page}&size=${HISTORY_PAGE_SIZE}`;

export type HistoryLine = { text: string; changed: boolean };

export type HistoryField = {
  key: 'baseFee' | 'freeShippingThreshold' | 'remoteAreas';
  label: string;
  /** 처음 만든 교체면 null */
  before: HistoryLine[] | null;
  after: HistoryLine[];
  changed: boolean;
};

export type HistoryComparison = {
  /** initial — 처음 설정, unchanged — 값이 그대로인 교체(같은 본문 재전송, version 만 오른다) */
  kind: 'initial' | 'changed' | 'unchanged';
  fields: HistoryField[];
};

type Area = DomesticPolicySnapshot['remoteAreas'][number];

const NONE = '없음';

const sameMoney = (a: Money | null, b: Money | null) =>
  a === null || b === null ? a === b : a.amount === b.amount && a.currency === b.currency;

/** 구간 비교 키 — 아이디가 없어 값 전체로 같은 구간인지 본다 */
const areaKey = (area: Area) =>
  JSON.stringify([
    area.name,
    area.postalCodeFrom,
    area.postalCodeTo,
    area.surcharge.amount,
    area.surcharge.currency,
  ]);

const areaText = (area: Area) =>
  `${area.name} ${area.postalCodeFrom}~${area.postalCodeTo} +${formatMoney(area.surcharge)}`;

const moneyLines = (money: Money | null, changed: boolean): HistoryLine[] => [
  { text: money ? formatMoney(money) : NONE, changed },
];

/** 구간 줄 — 반대쪽에 같은 구간이 없는 줄만 바뀐 줄로 표시한다 */
const areaLines = (areas: Area[], other: Set<string> | null, changed: boolean): HistoryLine[] =>
  areas.length === 0
    ? [{ text: NONE, changed }]
    : areas.map((area) => ({
        text: areaText(area),
        changed: other !== null && !other.has(areaKey(area)),
      }));

/**
 * 이력 한 건의 교체 전 · 후 값을 칸마다 맞대어 바뀐 칸을 가른다. 금액은 서버 값을 `formatMoney` 로 옮겨 적을 뿐 계산하지 않는다.
 * version 은 비교하지 않는다 — 같은 본문 재전송도 version 이 올라 이력이 남는다(`unchanged`).
 */
export function compareHistory({ before, after }: DomesticPolicyHistoryItem): HistoryComparison {
  if (before === null) {
    return {
      kind: 'initial',
      fields: [
        {
          key: 'baseFee',
          label: '기본 배송비',
          before: null,
          after: moneyLines(after.baseFee, false),
          changed: false,
        },
        {
          key: 'freeShippingThreshold',
          label: '무료배송 기준',
          before: null,
          after: moneyLines(after.freeShippingThreshold, false),
          changed: false,
        },
        {
          key: 'remoteAreas',
          label: '도서산간 구간',
          before: null,
          after: areaLines(after.remoteAreas, null, false),
          changed: false,
        },
      ],
    };
  }

  const baseFeeChanged = !sameMoney(before.baseFee, after.baseFee);
  const thresholdChanged = !sameMoney(before.freeShippingThreshold, after.freeShippingThreshold);
  const beforeAreas = new Set(before.remoteAreas.map(areaKey));
  const afterAreas = new Set(after.remoteAreas.map(areaKey));
  const areasChanged =
    before.remoteAreas.length !== after.remoteAreas.length ||
    before.remoteAreas.some((area, i) => {
      const next = after.remoteAreas[i];
      return next === undefined || areaKey(area) !== areaKey(next);
    });

  const fields: HistoryField[] = [
    {
      key: 'baseFee',
      label: '기본 배송비',
      before: moneyLines(before.baseFee, baseFeeChanged),
      after: moneyLines(after.baseFee, baseFeeChanged),
      changed: baseFeeChanged,
    },
    {
      key: 'freeShippingThreshold',
      label: '무료배송 기준',
      before: moneyLines(before.freeShippingThreshold, thresholdChanged),
      after: moneyLines(after.freeShippingThreshold, thresholdChanged),
      changed: thresholdChanged,
    },
    {
      key: 'remoteAreas',
      label: '도서산간 구간',
      before: areaLines(before.remoteAreas, afterAreas, areasChanged),
      after: areaLines(after.remoteAreas, beforeAreas, areasChanged),
      changed: areasChanged,
    },
  ];
  return { kind: fields.some((field) => field.changed) ? 'changed' : 'unchanged', fields };
}

/**
 * 이력 읽기 실패 — 401 · 403 은 안내 상태, 그 밖(연결 · 서버 · 형식)은 다시 불러오기.
 * 503 `DELIVERY_013`(관리자 이름 조회 실패)은 문구만 달리 한 다시 불러오기다.
 */
export function historyLoadFailure(
  cause: unknown,
): { kind: 'denied'; denied: AccessDenied } | { kind: 'failed'; message: string } {
  const denied = accessDeniedOf(cause);
  if (denied) return { kind: 'denied', denied };
  const adminName = cause instanceof ApiError && cause.code === ADMIN_NAME_UNAVAILABLE_CODE;
  return { kind: 'failed', message: adminName ? ADMIN_NAME_FAILED : LOAD_FAILED };
}
