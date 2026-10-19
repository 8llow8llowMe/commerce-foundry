import { ApiError } from '@cf/api-client';
import type { Metadata } from 'next';
import { Link } from '@/components/i18n/Link';
import { notFound } from 'next/navigation';
import { cache, Suspense } from 'react';
import { ProductGallery } from '@/components/product/detail/ProductGallery';
import { PurchasePanel, type PurchaseProduct } from '@/components/product/detail/PurchasePanel';
import { ProductLooks } from '@/components/product/look/ProductLooks';
import { productJsonLd } from '@/lib/catalog/product-jsonld';
import { shopHref } from '@/lib/catalog/shop-query';
import type { ProductDetail } from '@/lib/catalog/types';
import { getI18n, localeAlternates } from '@/lib/i18n/server';
import { sanitizeProductHtml } from '@/lib/sanitize-html';
import { serverFetch } from '@/lib/server-api';

/**
 * 상품 하나. 없는 상품 · 숨긴 상품(404 PRODUCT_001)과 숫자가 아닌 id(BE 는 400)는 null → not-found.
 * generateMetadata 와 페이지가 같은 요청 안에서 한 번만 부르도록 cache 로 묶는다.
 */
const getProduct = cache(async (productId: string): Promise<ProductDetail | null> => {
  if (!/^\d{1,19}$/.test(productId)) return null;
  try {
    return await serverFetch<ProductDetail>(`/api/v1/products/${productId}`);
  } catch (cause) {
    if (cause instanceof ApiError && cause.kind === 'notFound') return null;
    throw cause;
  }
});

export async function generateMetadata({
  params,
}: PageProps<'/[locale]/products/[productId]'>): Promise<Metadata> {
  const product = await getProduct((await params).productId).catch(() => null);
  if (!product) return {};
  const description = product.summary ?? undefined;
  return {
    title: product.name,
    description,
    alternates: await localeAlternates(`/products/${product.productId}`),
    openGraph: {
      title: product.name,
      description,
      images: product.thumbnail
        ? [
            {
              url: product.thumbnail.url,
              width: product.thumbnail.width ?? undefined,
              height: product.thumbnail.height ?? undefined,
              alt: product.thumbnail.altText ?? product.name,
            },
          ]
        : undefined,
    },
  };
}

export default async function ProductPage({ params }: PageProps<'/[locale]/products/[productId]'>) {
  const { productId } = await params;
  const { href, dict } = await getI18n();

  let product: ProductDetail | null;
  try {
    product = await getProduct(productId);
  } catch {
    return (
      <main className="mx-auto flex max-w-xl flex-col items-center gap-4 px-4 py-24 text-center">
        <p className="text-sm text-muted-fg">{dict.product.loadFailed}</p>
        {/* 같은 URL 이라 문서를 새로 받는다 */}
        <a href={href(`/products/${productId}`)} className="text-sm underline underline-offset-4">
          {dict.product.reload}
        </a>
      </main>
    );
  }
  if (!product) notFound();

  // 클라이언트 컴포넌트에는 필요한 것만 넘긴다(원문 HTML · 갤러리는 서버에서만 쓴다)
  const { descriptionHtml, images, categoryPath, ...purchase } = product;
  const purchaseProduct: PurchaseProduct = purchase;

  return (
    <main className="mx-auto max-w-screen-xl px-4 pt-6 pb-24 lg:px-8 lg:pt-10">
      {categoryPath.length > 0 && (
        <nav
          aria-label={dict.product.breadcrumb}
          className="mb-6 text-xs tracking-[0.15em] text-muted-fg"
        >
          <ol className="flex flex-wrap gap-2">
            {categoryPath.map((category, index) => (
              <li key={category.categoryId} className="flex gap-2">
                {index > 0 && <span aria-hidden>/</span>}
                <Link
                  href={shopHref({ categoryId: category.categoryId, sort: 'NEWEST' })}
                  className="hover:text-fg"
                >
                  {category.name}
                </Link>
              </li>
            ))}
          </ol>
        </nav>
      )}

      <div className="lg:grid lg:grid-cols-[minmax(0,3fr)_minmax(0,2fr)] lg:gap-12">
        <ProductGallery name={product.name} thumbnail={product.thumbnail} images={images} />

        {/* PC 에서는 이미지를 내리는 동안 구매 정보가 따라온다(헤더 높이만큼 띄운다) */}
        <div className="mt-8 flex flex-col gap-6 lg:sticky lg:top-32 lg:mt-0 lg:self-start">
          <div className="flex flex-col gap-3">
            <h1 className="font-serif text-2xl leading-snug lg:text-3xl">{product.name}</h1>
            {product.summary && <p className="text-sm text-muted-fg">{product.summary}</p>}
          </div>
          <PurchasePanel product={purchaseProduct} />
        </div>
      </div>

      {/* 룩은 덧붙이는 영역이라 구매 정보를 기다리게 하지 않는다 */}
      <Suspense fallback={null}>
        <ProductLooks productId={product.productId} />
      </Suspense>

      {descriptionHtml && (
        <section aria-labelledby="product-description" className="mx-auto mt-20 max-w-3xl">
          <h2
            id="product-description"
            className="border-t border-border pt-10 text-center font-display text-2xl tracking-[0.15em] uppercase"
          >
            Details
          </h2>
          <div
            className="mt-8 text-sm leading-7 [&_a]:underline [&_h2]:mt-10 [&_h2]:font-serif [&_h2]:text-xl [&_h3]:mt-8 [&_h3]:font-serif [&_h3]:text-lg [&_img]:my-6 [&_img]:h-auto [&_img]:w-full [&_li]:ml-5 [&_ol]:list-decimal [&_p]:my-4 [&_table]:my-6 [&_table]:w-full [&_td]:border-b [&_td]:border-border [&_td]:py-2 [&_th]:border-b [&_th]:border-border [&_th]:py-2 [&_th]:text-left [&_ul]:list-disc"
            // 서버에서 허용 목록으로 거른 HTML 만 넣는다 (lib/sanitize-html.ts)
            dangerouslySetInnerHTML={{ __html: sanitizeProductHtml(descriptionHtml) }}
          />
        </section>
      )}

      <script
        type="application/ld+json"
        // productJsonLd 가 < 를 이스케이프한다
        dangerouslySetInnerHTML={{ __html: productJsonLd(product) }}
      />
    </main>
  );
}
