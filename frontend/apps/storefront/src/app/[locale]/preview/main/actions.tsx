'use server';

import { brand } from '@cf/brand';
import type { ReactNode } from 'react';
import { HomeSections } from '@/components/home/HomeSections';
import { resolveHomeBlocks } from '@/lib/display/home-sections';
import { readPreviewDisplay } from '@/lib/display/preview';
import { hasLocale } from '@/lib/i18n/config';
import { getDictionary } from '@/lib/i18n/dictionaries';

export type PreviewRenderResult = { ok: true; node: ReactNode } | { ok: false };

/**
 * 어드민 메인 전시 미리보기(#71) — 어드민이 BE 미리보기(#340)에서 받아 넘긴 응답(고객 메인 응답과 같은 모양)을
 * 메인과 같은 경로(`resolveHomeBlocks` → `<HomeSections>`, `loadHomeBlocks` 와 같다)로 그린다. 이 액션은 게이트웨이를 부르지 않는다 —
 * 상품 카드는 응답에 있다. 다만 응답에 그릴 섹션이 없어 브랜드 설정 메인이 되거나 LOOK 이 있으면, 설정 상품 픽 · 룩 구성품은 메인처럼 따로 받는다
 * (서버 액션은 root params 를 읽을 수 없어 언어를 인자로 받고 토큰 없이 부른다).
 * 누구나 부를 수 있는 POST 라 입력은 `readPreviewDisplay` 가 모양 · 상한을 다시 보고, 결과는 부른 브라우저에만 돌아간다.
 */
export async function renderMainPreview(
  locale: string,
  display: unknown,
): Promise<PreviewRenderResult> {
  const response = readPreviewDisplay(display);
  if (!hasLocale(locale) || response === null) return { ok: false };

  const blocks = resolveHomeBlocks(
    response,
    brand.content[locale].home,
    getDictionary(locale).home,
  );
  return { ok: true, node: <HomeSections sections={blocks} fetchLocale={locale} /> };
}
