/** 본문 영역 아이디 — `AdminShell` 의 `<main>` 이 단다 */
export const MAIN_ID = 'main';

/**
 * 키보드 · 화면 낭독기 사용자를 위한 "본문으로 건너뛰기"(#358). 평소에는 보이지 않고 Tab 으로 처음 닿으면 왼쪽 위에 보인다.
 * 좁은 화면에서는 메뉴가 본문 위로 와(스무 개 넘는 링크) 시작 카드까지 Tab 을 한참 눌러야 하므로 둔다.
 */
export function SkipLink() {
  return (
    <a
      href={`#${MAIN_ID}`}
      className="sr-only rounded-md bg-bg px-4 py-2 text-sm font-medium text-fg focus:not-sr-only focus:fixed focus:top-2 focus:left-2 focus:z-50 focus:outline-2 focus:outline-primary"
    >
      본문으로 건너뛰기
    </a>
  );
}
