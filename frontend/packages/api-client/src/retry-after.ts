/**
 * `Retry-After` 헤더를 초 단위로 읽는다. 값은 delta-seconds(`120`) 또는 HTTP-date 다(RFC 9110 §10.2.3).
 * 게이트웨이는 delta-seconds 를 보내지만(GATEWAY_006), 둘 다 받아 둔다. 읽을 수 없으면 null 이다.
 */
export function parseRetryAfter(value: string | null, now: number = Date.now()): number | null {
  if (value === null) return null;
  const trimmed = value.trim();
  if (trimmed === '') return null;

  if (/^\d+$/.test(trimmed)) return Number(trimmed);

  // HTTP-date 는 세 형식 모두 요일 이름으로 시작한다. Date.parse 는 '-1' · '1.5' 도 날짜로 받으므로 먼저 거른다.
  if (!/^[A-Za-z]{3}/.test(trimmed)) return null;
  const at = Date.parse(trimmed);
  if (Number.isNaN(at)) return null;
  return Math.max(0, Math.ceil((at - now) / 1000));
}
