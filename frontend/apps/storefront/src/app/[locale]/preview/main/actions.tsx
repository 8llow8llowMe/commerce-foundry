'use server';

import { brand } from '@cf/brand';
import type { ReactNode } from 'react';
import { HomeSections } from '@/components/home/HomeSections';
import type { ProductDetail, ProductListItem } from '@/lib/catalog/types';
import { resolveHomeBlocks } from '@/lib/display/home-sections';
import {
  isLiveAt,
  PREVIEW_LIMITS,
  previewProductIds,
  productCardOf,
  readPreviewDraft,
  toPreviewResponse,
} from '@/lib/display/preview';
import { hasLocale } from '@/lib/i18n/config';
import { getDictionary } from '@/lib/i18n/dictionaries';
import { serverFetch } from '@/lib/server-api';

export type PreviewRenderResult = { ok: true; node: ReactNode } | { ok: false };

/**
 * 어드민 메인 전시 미리보기(#71) — 저장 전 편집 상태를 메인과 같은 블록으로 서버에서 그린다.
 * 아무것도 저장하지 않고 공개 전시 캐시(`loadHomeBlocks`)를 지나지 않는다. 상품은 토큰 없이 공개 상세로 받고(no-store),
 * 숨김 · 삭제 상품은 받지 못해 빠진다(메인과 같다). 서버 액션은 root params 를 읽을 수 없어 언어를 인자로 받는다.
 * 누구나 부를 수 있는 POST 라 입력은 `readPreviewDraft` 가 상한까지만 읽고, 결과는 부른 브라우저에만 돌아간다.
 */
export async function renderMainPreview(
  locale: string,
  draft: unknown,
): Promise<PreviewRenderResult> {
  const sections = readPreviewDraft(draft);
  if (!hasLocale(locale) || sections === null) return { ok: false };

  const now = Date.now();
  const live = sections.filter((section) => isLiveAt(section, now));
  const ids = previewProductIds(live);
  const cards = new Map<string, ProductListItem>();
  for (let start = 0; start < ids.length; start += PREVIEW_LIMITS.concurrency) {
    const details = await Promise.allSettled(
      ids
        .slice(start, start + PREVIEW_LIMITS.concurrency)
        .map((id) =>
          serverFetch<ProductDetail>(
            `/api/v1/products/${id}`,
            { headers: { 'accept-language': locale } },
            { anonymous: true },
          ),
        ),
    );
    for (const result of details) {
      if (result.status === 'fulfilled') {
        cards.set(result.value.productId, productCardOf(result.value));
      }
    }
  }

  const dict = getDictionary(locale);
  const blocks = resolveHomeBlocks(
    toPreviewResponse(live, cards),
    brand.content[locale].home,
    dict.home,
  );
  return { ok: true, node: <HomeSections sections={blocks} fetchLocale={locale} /> };
}
