/**
 * 사전 문구의 자리표시자 `{name}` 을 값으로 바꾼다. 사전은 서버 → 클라이언트 props 로 넘어가므로
 * 함수 대신 문자열 템플릿으로 둔다. 값이 없는 자리는 그대로 남겨 빠진 값을 화면에서 알 수 있게 한다.
 */
export function fill(template: string, values: Record<string, string | number>): string {
  return template.replace(/\{(\w+)\}/g, (match, key: string) =>
    key in values ? String(values[key]) : match,
  );
}
