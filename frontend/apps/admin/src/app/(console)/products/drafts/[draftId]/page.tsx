import type { Metadata } from 'next';
import { notFound } from 'next/navigation';
import { AccessNotice } from '@/components/AccessNotice';
import { RefreshButton } from '@/components/product/RefreshButton';
import { ProductDraftDetailView } from '@/components/product-draft/ProductDraftDetailView';
import { LOAD_FAILED } from '@/lib/api-errors';
import { flattenTree } from '@/lib/category/tree';
import { CATEGORIES_PATH, type CategoryTree } from '@/lib/category/types';
import { categoryLabel, loadProductDraft } from '@/lib/product-draft/detail';
import { serverFetch } from '@/lib/server-api';

export const metadata: Metadata = { title: 'AI 상품 초안' };

/**
 * AI 상품 초안 상세(#360 · BE #359). 서버 컴포넌트에서 읽고, 새로고침 · 거절 뒤에는 `router.refresh()` 로 다시 읽는다.
 * 초안 목록 API 가 없어 다시 찾기는 이 주소뿐이다. 카테고리 이름은 곁가지다 — 못 읽어도 상세는 보인다.
 */
export default async function ProductDraftDetailPage({
  params,
}: PageProps<'/products/drafts/[draftId]'>) {
  const { draftId } = await params;
  const loaded = await loadProductDraft(draftId, serverFetch);

  if (loaded.kind === 'notFound') notFound();
  if (loaded.kind === 'denied') return <AccessNotice denied={loaded.denied} />;
  if (loaded.kind === 'failed') {
    return (
      <div role="alert" className="flex items-center gap-4">
        <p className="text-sm text-danger">{LOAD_FAILED}</p>
        <RefreshButton>다시 불러오기</RefreshButton>
      </div>
    );
  }
  const { draft } = loaded;
  const rows =
    draft.facts.categoryId === null
      ? null
      : await serverFetch<CategoryTree>(CATEGORIES_PATH).then(
          (tree) => flattenTree(tree.categories),
          () => null,
        );
  return (
    <ProductDraftDetailView
      draft={draft}
      categoryName={categoryLabel(draft.facts.categoryId, rows)}
    />
  );
}
