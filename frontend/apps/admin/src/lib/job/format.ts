const dateTime = new Intl.DateTimeFormat('ko-KR', {
  dateStyle: 'short',
  timeStyle: 'medium',
  timeZone: 'Asia/Seoul',
});

/** 작업 시각(UTC ISO) → 한국 시각. 없거나 읽을 수 없으면 대시 */
export function formatJobTime(value: string | null): string {
  if (value === null) return '—';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '—' : dateTime.format(date);
}
