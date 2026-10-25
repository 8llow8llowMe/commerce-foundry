import type { Locale } from '@cf/ui';
import type { Dictionary } from '@/lib/i18n/dictionaries';
import { fill } from '@/lib/i18n/format';
import type { Preorder } from './types';

type PreorderLabels = Dictionary['product']['preorder'];

const DATE_ONLY = /^(\d{4})-(\d{2})-(\d{2})$/;

/**
 * BE 의 날짜만 있는 값(`yyyy-MM-dd`, 스토어 현지 날짜)을 읽는다. 시간대를 붙이지 않은 값이라
 * `new Date('2026-11-20')`(UTC 자정 → 현지에서 하루 밀릴 수 있다) 대신 UTC 날짜로 고정해 다룬다.
 * 형식이 틀리거나 없는 날짜(2월 30일)면 null.
 */
export function parseDateOnly(value: string): Date | null {
  const match = DATE_ONLY.exec(value);
  if (!match) return null;
  const [year, month, day] = [Number(match[1]), Number(match[2]), Number(match[3])];
  const date = new Date(Date.UTC(year, month - 1, day));
  if (date.getUTCFullYear() !== year || date.getUTCMonth() !== month - 1) return null;
  if (date.getUTCDate() !== day) return null;
  return date;
}

/**
 * 발송 예정일을 스토어 언어로 — 국문 '11월 20일', 영문 'November 20'. 올해가 아니면 연도를 붙인다.
 * `today` 는 호출하는 쪽이 넘긴다(서버 · 클라이언트가 같은 값을 쓰게, 테스트에서 고정하게).
 */
export function formatShipDate(date: Date, locale: Locale, today: Date): string {
  const sameYear = date.getUTCFullYear() === today.getFullYear();
  return new Intl.DateTimeFormat(locale === 'ko' ? 'ko-KR' : 'en-US', {
    timeZone: 'UTC',
    month: 'long',
    day: 'numeric',
    ...(sameYear ? {} : { year: 'numeric' }),
  }).format(date);
}

/**
 * 프리오더 안내 한 줄(#60) — '프리오더 · 11월 20일부터 순차 발송'. 발송 예정일이 없거나 읽을 수 없으면
 * '프리오더 · 발송일 추후 안내'. 프리오더가 아니면 null(일반 배송 안내만 보인다).
 */
export function preorderNote(
  preorder: Preorder | null,
  locale: Locale,
  labels: PreorderLabels,
  today: Date,
): string | null {
  if (!preorder) return null;
  const date = preorder.expectedShipDate ? parseDateOnly(preorder.expectedShipDate) : null;
  if (!date) return labels.shipDateTbd;
  return fill(labels.shipsFrom, { date: formatShipDate(date, locale, today) });
}
