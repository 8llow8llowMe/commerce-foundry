import { ApiError } from '@cf/api-client';
import type { Metadata } from 'next';
import { Link } from '@/components/i18n/Link';
import { notFound } from 'next/navigation';
import { cache, Suspense } from 'react';
import { ProductGallery } from '@/components/product/detail/ProductGallery';
import { ProductInfoAccordion } from '@/components/product/detail/ProductInfoAccordion';
import { PurchasePanel, type PurchaseProduct } from '@/components/product/detail/PurchasePanel';
import { ProductLooks } from '@/components/product/look/ProductLooks';
import { deliveryNote } from '@/lib/catalog/delivery-note';
import { loadShippingNotice } from '@/lib/catalog/load-shipping-notice';
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
  const { href, dict, content } = await getI18n();
  // 상품과 함께 읽는다 — 실패해도 null 이라 상품 화면을 막지 않는다
  const shippingNotice = loadShippingNotice();

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
  const shipping = await shippingNotice;

  return (
    // 모바일은 첫 화면에 사진 · 이름 · 가격 · 배송 안내가 함께 들어오도록 사진을 헤더 바로 아래에 붙이고
    // 경로(breadcrumb)는 PC 에서만 보인다(#60, 390×844 기준)
    <main className="mx-auto max-w-screen-xl px-4 pb-24 lg:px-8 lg:pt-10">
      {categoryPath.length > 0 && (
        <nav
          aria-label={dict.product.breadcrumb}
          className="mb-6 hidden text-xs tracking-[0.15em] text-muted-fg lg:block"
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

      {/* PC: 왼쪽 7열 이미지 세로 이어 붙이기, 오른쪽 5열 구매 정보(#60) */}
      <div className="lg:grid lg:grid-cols-12 lg:gap-x-12">
        <div className="lg:col-span-7">
          <ProductGallery name={product.name} thumbnail={product.thumbnail} images={images} />
        </div>

        {/* PC 에서는 이미지를 내리는 동안 구매 정보가 따라온다(헤더 높이만큼 띄운다) */}
        <div className="mt-5 flex flex-col gap-4 lg:sticky lg:col-span-5 lg:top-[calc(var(--site-header-h)+1.5rem)] lg:mt-0 lg:self-start">
          <h1 className="font-serif text-xl leading-snug">{product.name}</h1>
          <PurchasePanel
            product={purchaseProduct}
            delivery={deliveryNote(product.customOrder, shipping, dict.product.delivery)}
          />
          {product.summary && <p className="text-sm text-muted-fg">{product.summary}</p>}
          <ProductInfoAccordion
            info={content.productInfo}
            shippingFee={shipping?.lines ?? []}
            labels={dict.product.info}
          />
        </div>
      </div>

      {/* 룩은 덧붙이는 영역이라 구매 정보를 기다리게 하지 않는다 */}
      <Suspense fallback={null}>
        <ProductLooks productId={product.productId} />
      </Suspense>

      {descriptionHtml && (
        <section aria-labelledby="product-description" className="mx-auto mt-24 max-w-3xl lg:mt-32">
          <h2
            id="product-description"
            className="text-center font-display text-2xl tracking-[0.15em] uppercase"
          >
            Details
          </h2>
          <div
            className="mt-8 leading-7 [&_a]:underline [&_h2]:mt-10 [&_h2]:font-serif [&_h2]:text-xl [&_h3]:mt-8 [&_h3]:font-serif [&_h3]:text-lg [&_img]:my-6 [&_img]:h-auto [&_img]:w-full [&_li]:ml-5 [&_ol]:list-decimal [&_p]:my-4 [&_table]:my-6 [&_table]:w-full [&_td]:border-b [&_td]:border-border [&_td]:py-2 [&_th]:border-b [&_th]:border-border [&_th]:py-2 [&_th]:text-left [&_ul]:list-disc"
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
