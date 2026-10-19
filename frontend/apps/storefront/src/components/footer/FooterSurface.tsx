'use client';

import type { HomeMediaSlot } from '@cf/ui';
import { useSelectedLayoutSegment } from 'next/navigation';
import type { ReactNode } from 'react';

/**
 * 푸터 바탕. 메인에서만 전폭 화보를 밝기 0.62 로 깔고 글을 Porcelain 으로 바꾼다(#59 푸터).
 * 목록 · 상세는 지금처럼 muted 면이다.
 *
 * 메인 판별은 [locale] 레이아웃 바로 아래 세그먼트가 없는지(=그 레이아웃의 page)로 한다.
 * usePathname 은 proxy rewrite(언어 호스트 · /en) 뒤에 브라우저 주소와 다를 수 있어 쓰지 않는다.
 * 화보가 아직 없으면(image null) 같은 어두운 단색 면 — 밝기를 낮춘 빈 자리 위 밝은 글은 대비가 모자라다.
 */
export function FooterSurface({
  backdrop,
  children,
}: {
  backdrop: HomeMediaSlot | null;
  children: ReactNode;
}) {
  const onHome = useSelectedLayoutSegment() === null;
  const dark = onHome && backdrop !== null;
  const image = dark ? backdrop.image : null;

  return (
    <footer
      data-backdrop={dark ? '' : undefined}
      className="group/footer relative isolate overflow-hidden bg-muted text-fg data-backdrop:bg-fg data-backdrop:text-bg"
    >
      {image && (
        // 장식 배경 — 내용은 글로 다 있다. 푸터는 맨 아래라 lazy
        // eslint-disable-next-line @next/next/no-img-element
        <img
          src={image.src}
          alt=""
          width={image.width}
          height={image.height}
          loading="lazy"
          decoding="async"
          className="absolute inset-0 -z-10 size-full object-cover brightness-[0.62]"
        />
      )}
      {children}
    </footer>
  );
}
