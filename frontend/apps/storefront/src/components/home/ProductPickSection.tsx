import type { SliceResponse } from '@cf/api-client';
import type { ProductPickSection as ProductPickSectionData } from '@cf/ui';
import Link from 'next/link';
import { ProductCard } from '@/components/product/ProductCard';
import { productsApiPath } from '@/lib/catalog/shop-query';
import type { ProductListItem } from '@/lib/catalog/types';
import { serverFetch } from '@/lib/server-api';
import { SectionHeading } from './SectionHeading';

/**
 * 02 대표 상품. 상품 목록 API 에서 고른다 — 이름 · 가격을 설정에 적지 않는다.
 * 못 받았거나 비었으면 메인을 깨뜨리지 않고 섹션을 그리지 않는다.
 */
export async function ProductPickSection({ section }: { section: ProductPickSectionData }) {
  let products: ProductListItem[];
  try {
    const slice = await serverFetch<SliceResponse<ProductListItem>>(
      productsApiPath(
        { categoryId: section.source.categoryId, sort: 'NEWEST' },
        0,
        section.source.limit,
      ),
    );
    products = slice.contents.slice(0, section.source.limit);
  } catch {
    return null;
  }
  if (products.length === 0) return null;

  const headingId = `${section.id}-title`;
  return (
    <section aria-labelledby={headingId} className="mx-auto w-full max-w-screen-xl px-4 lg:px-8">
      <div className="mb-8 flex items-end justify-between gap-4 lg:mb-12">
        <SectionHeading id={headingId} title={section.title} />
        {section.viewAll && (
          <Link
            href={section.viewAll.href}
            className="shrink-0 border-b border-current pb-1 text-xs tracking-[0.2em] uppercase hover:opacity-70"
          >
            {section.viewAll.label} <span aria-hidden>→</span>
          </Link>
        )}
      </div>
      <ul className="grid grid-cols-2 gap-x-4 gap-y-10 lg:grid-cols-4 lg:gap-x-6">
        {products.map((product) => (
          <li key={product.productId}>
            <ProductCard product={product} />
          </li>
        ))}
      </ul>
    </section>
  );
}
