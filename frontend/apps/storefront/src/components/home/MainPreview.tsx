'use client';

import { useEffect, useRef, useState, type ReactNode } from 'react';
import type { PreviewRenderResult } from '@/app/[locale]/preview/main/actions';
import { useDictionary } from '@/components/i18n/LocaleProvider';
import { isPreviewDraft, PREVIEW_READY } from '@/lib/display/preview';

/**
 * 어드민 메인 전시 미리보기(#71)의 iframe 쪽. 부모 창이 허용한 어드민 origin 일 때만 편집 상태를 받는다
 * (`event.origin` · `event.source` 둘 다 본다) — 받은 상태는 서버 액션이 메인과 같은 블록으로 그려 돌려준다.
 * 새 상태를 그리는 동안에는 앞 화면을 둔다. 늦게 온 이전 결과는 버린다.
 * 미리보기 안의 링크 · 버튼은 누르지 않는다 — 실제 페이지로 옮기거나 장바구니에 담지 않게(보기 전용).
 */
export function MainPreview({
  locale,
  adminOrigin,
  render,
}: {
  locale: string;
  adminOrigin: string | null;
  /**
   * 편집 상태를 그리는 서버 액션. 페이지(서버)가 넘긴다 — 액션이 돌려주는 블록의 클라이언트 컴포넌트가
   * 이 페이지의 클라이언트 매니페스트에 들어가려면 액션 모듈이 페이지의 서버 그래프에 있어야 한다
   */
  render: (locale: string, draft: unknown) => Promise<PreviewRenderResult>;
}) {
  const dict = useDictionary();
  const [node, setNode] = useState<ReactNode>(null);
  const [failed, setFailed] = useState(false);
  const generation = useRef(0);

  useEffect(() => {
    if (!adminOrigin || window.parent === window) return;
    const onMessage = (event: MessageEvent) => {
      if (!isPreviewDraft(event, adminOrigin, window.parent)) return;
      const current = ++generation.current;
      render(locale, event.data).then(
        (result) => {
          if (current !== generation.current) return;
          setFailed(!result.ok);
          if (result.ok) setNode(result.node);
        },
        () => {
          if (current === generation.current) setFailed(true);
        },
      );
    };
    window.addEventListener('message', onMessage);
    window.parent.postMessage({ type: PREVIEW_READY }, adminOrigin);
    return () => window.removeEventListener('message', onMessage);
  }, [adminOrigin, locale, render]);

  return (
    <div
      onClickCapture={(event) => {
        if ((event.target as Element).closest('a, button')) {
          event.preventDefault();
          event.stopPropagation();
        }
      }}
    >
      {failed && (
        <p role="alert" className="px-4 py-3 text-center text-sm text-danger">
          {dict.home.preview.failed}
        </p>
      )}
      {node ?? (
        <p className="px-4 py-24 text-center text-sm text-muted-fg">{dict.home.preview.waiting}</p>
      )}
    </div>
  );
}
