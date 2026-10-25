// 전시 문구 · 링크 · 기간 입력 규칙 — BE 요청 검증 · 섹션 규칙과 같은 판정이다(`backend/docs/api-design-guide.md` §2-9).
// 정본은 서버다. 화면은 같은 규칙으로 미리 막아 운영자가 저장 버튼을 누른 뒤에야 알게 되는 일을 줄인다.

/** BE 처럼 앞뒤 공백(전각 공백 포함)을 빼고, 비면 없는 값(null)으로 본다 — `TextValues.normalize` */
export function normalizeText(value: string): string | null {
  const stripped = value.trim();
  return stripped === '' ? null : stripped;
}

// 제어 문자(\p{Cc})와 짝 없는 서로게이트(\p{Cs})는 거절한다. 본문만 개행(CR · LF) · 탭을 허용한다 — BE `DisplayTextValidator`
const SINGLE_LINE_REJECTED = /[\p{Cc}\p{Cs}]/u;
const MULTILINE_REJECTED = /(?![\n\r\t])[\p{Cc}\p{Cs}]/u;

export function hasRejectedCharacter(value: string, multiline = false): boolean {
  return (multiline ? MULTILINE_REJECTED : SINGLE_LINE_REJECTED).test(value);
}

// 링크 — BE `DisplaySection.isAllowedLinkUrl`. 공백 · 제어 · 서식 문자 · 역슬래시는 어디에도 쓸 수 없고,
// java.net.URI 가 받지 않는 글자(" < > ^ ` { | } [ ])와 잘못된 퍼센트 인코딩도 거른다.
const LINK_REJECTED_CHARACTER = /[\s\p{Zs}\p{Cc}\p{Cf}\p{Cs}\\"<>^`{|}[\]]/u;
const BAD_PERCENT = /%(?![0-9A-Fa-f]{2})/;
/** https 절대 주소 — 호스트가 있고 사용자 정보(`a@b`)가 없다. 스킴은 대소문자를 가리지 않는다(BE 가 소문자로 비교) */
const HTTPS_URL = /^https:\/\/([^/?#]*)/i;

/** `/` 로 시작하는 스토어 안 상대 경로(`//host` 제외) 또는 https 주소인가 */
export function isAllowedLinkUrl(url: string): boolean {
  if (url === '' || LINK_REJECTED_CHARACTER.test(url) || BAD_PERCENT.test(url)) return false;
  if (url.startsWith('/')) return !url.startsWith('//');
  const authority = HTTPS_URL.exec(url)?.[1];
  if (!authority || authority.includes('@')) return false;
  const host = authority.replace(/:\d*$/, '');
  return host !== '';
}

// 노출 기간 — 화면 칸은 `<input type="datetime-local">` 글자(이 컴퓨터 시간대)다. 보낼 때 UTC ISO 로 바꾼다.
const LOCAL_DATE_TIME = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.\d{1,3})?)?$/;

/**
 * datetime-local 글자 → UTC ISO. 형식이 틀리거나 연도가 0001 ~ 9999 밖이면 null(BE `PRODUCT_165` 와 같은 범위).
 * 시간대 표기가 없는 날짜 · 시각은 이 컴퓨터 시간대로 읽힌다(ECMAScript Date 규칙).
 */
export function localInputToIso(value: string): string | null {
  const match = LOCAL_DATE_TIME.exec(value);
  if (!match || Number(match[1]) < 1) return null;
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return null;
  // 9999-12-31 23:59 (+09:00) 처럼 UTC 로 바꾸면 범위를 넘는 경우도 BE 범위(UTC 기준)로 거른다
  const year = date.getUTCFullYear();
  if (year < 1 || year > 9999) return null;
  return date.toISOString();
}

const pad = (value: number, length = 2) => String(value).padStart(length, '0');

/** UTC ISO → datetime-local 글자(이 컴퓨터 시간대, 분 단위). 없거나 읽을 수 없으면 빈 글자 */
export function isoToLocalInput(iso: string | null): string {
  if (!iso) return '';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '';
  return (
    `${pad(date.getFullYear(), 4)}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    `T${pad(date.getHours())}:${pad(date.getMinutes())}`
  );
}

/** 목록에 보이는 시각 — `2026.11.01 09:00`(이 컴퓨터 시간대) */
export function formatDateTime(iso: string): string {
  const local = isoToLocalInput(iso);
  return local ? local.replace(/-/g, '.').replace('T', ' ') : iso;
}

/** 목록의 노출 기간 한 줄 */
export function periodLabel(startAt: string | null, endAt: string | null): string {
  if (!startAt && !endAt) return '기간 제한 없음';
  const start = startAt ? formatDateTime(startAt) : '';
  const end = endAt ? formatDateTime(endAt) : '';
  if (!endAt) return `${start}부터`;
  if (!startAt) return `${end}까지`;
  return `${start} ~ ${end}`;
}
