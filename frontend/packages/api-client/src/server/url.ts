/** base 끝 슬래시를 지우고 경로를 붙인다. 게이트웨이는 `//` 경로를 400 으로 거절한다. */
export function joinUrl(base: string, pathAndQuery: string): string {
  return base.replace(/\/+$/, '') + pathAndQuery;
}
