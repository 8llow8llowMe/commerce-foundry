'use client';

import { useEffect, useRef, type ReactNode } from 'react';

/**
 * 섹션 첫 등장 모션(#57 §6). 섹션이 처음 보일 때 안의 `data-reveal` 요소가 제목 → 글 → 사진 순서로
 * 75ms 간격을 두고 24px 떠오르며 나타난다. 섹션당 한 번만. 움직임 스타일은 app/globals.css 에 있다.
 *
 * 기본은 다 보이는 상태다 — 서버 렌더 · 스크립트 실패 · 움직임 줄이기 설정이면 아무것도 숨기지 않는다.
 * 하이드레이션 뒤 아직 화면 아래에 있는 섹션만 숨김(pending)으로 바꾸고, 보이면 shown 으로 둔다.
 * 이미 화면에 걸친 섹션은 숨기지 않는다(보이던 것이 깜빡이지 않게). 상태는 React state 가 아니라 DOM 속성이라 다시 그리지 않는다.
 */
export function Reveal({ children }: { children: ReactNode }) {
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const element = ref.current;
    if (!element || typeof IntersectionObserver === 'undefined') return;
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;
    if (element.getBoundingClientRect().top < window.innerHeight) return;

    element.dataset.revealState = 'pending';
    const observer = new IntersectionObserver(
      (entries) => {
        if (!entries.some((entry) => entry.isIntersecting)) return;
        element.dataset.revealState = 'shown';
        observer.disconnect();
      },
      // 섹션 위쪽이 화면 아래에서 15% 올라왔을 때
      { rootMargin: '0px 0px -15% 0px' },
    );
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  return <div ref={ref}>{children}</div>;
}
