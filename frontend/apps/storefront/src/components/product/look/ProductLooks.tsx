import { cx } from '@cf/ui';
import { fetchLookProducts, fetchProductLooks } from '@/lib/catalog/look-products';
import type { LookImage as LookImageData } from '@/lib/catalog/types';
import { LookPicker } from './LookPicker';

function LookImage({ image, title }: { image: LookImageData | null; title: string }) {
  return (
    <div className="relative aspect-[4/5] overflow-hidden bg-muted">
      {image && (
        // 이미지 CDN 이 정해지지 않아 next/image 를 아직 쓰지 않는다 (상품 화면과 같다)
        // eslint-disable-next-line @next/next/no-img-element
        <img
          src={image.url}
          alt={image.altText ?? title}
          width={image.width ?? undefined}
          height={image.height ?? undefined}
          loading="lazy"
          decoding="async"
          className="absolute inset-0 size-full object-cover"
        />
      )}
    </div>
  );
}

/**
 * 상품 상세 SHOP THE LOOK — 이 상품이 들어간 룩(BE #62)마다 착장 화보 + 구성품 함께 담기.
 * 구성품 카드(`products`)는 순서 · 아이디만 쓰고, 옵션 · SKU 는 상품 상세 API 로 받는다(못 받은 상품은 빠진다).
 * 판매 종료 · 품절 구성품은 상세의 saleStatus 대로 보이되 고를 수 없다(`lookPickable`).
 * 구성품이 없으면(`products: []` · 상세를 모두 못 받음) 룩은 남기고 함께 담기만 숨긴다. 화보도 없으면 그 룩은 그리지 않는다.
 * 룩 하나면 화보 | 구성품 두 칸, 여럿이면 PC 3열 · 모바일 가로 스와이프(72vw)로 늘어놓는다.
 * 제목 · 설명 · 대체 텍스트는 평문이라 텍스트 노드로만 그리고, 설명의 개행은 white-space 로 살린다.
 */
export async function ProductLooks({ productId }: { productId: string }) {
  const looks = await fetchProductLooks(productId);
  if (looks.length === 0) return null;

  const details = await fetchLookProducts(
    looks.flatMap((look) => look.products.map((product) => product.productId)),
  );
  const visible = looks
    .map((look) => ({
      ...look,
      picks: look.products.flatMap((product) => details.get(product.productId) ?? []),
    }))
    .filter((look) => look.image !== null || look.picks.length > 0);
  if (visible.length === 0) return null;

  const single = visible.length === 1;

  return (
    <section aria-labelledby="shop-the-look" className="mt-24 lg:mt-32">
      <h2
        id="shop-the-look"
        className="text-center font-display text-2xl tracking-[0.15em] uppercase"
      >
        Shop the Look
      </h2>
      <ul
        className={cx(
          'mt-10',
          single
            ? 'mx-auto max-w-5xl'
            : 'relative -mx-4 flex snap-x snap-mandatory scroll-px-4 gap-6 overflow-x-auto px-4 lg:mx-0 lg:grid lg:grid-cols-3 lg:overflow-visible lg:px-0',
        )}
      >
        {visible.map((look) => (
          <li
            key={look.lookId}
            className={cx(
              single
                ? 'grid gap-8 lg:grid-cols-2 lg:gap-12'
                : 'flex w-[72vw] shrink-0 snap-start flex-col gap-6 lg:w-auto',
            )}
          >
            <LookImage image={look.image} title={look.title} />
            <div className="flex flex-col gap-4">
              <div className="flex flex-col gap-2">
                <h3 className="font-serif text-lg">{look.title}</h3>
                {look.description && (
                  <p className="leading-7 whitespace-pre-line">{look.description}</p>
                )}
              </div>
              {look.picks.length > 0 && (
                <LookPicker products={look.picks} currentProductId={productId} />
              )}
            </div>
          </li>
        ))}
      </ul>
    </section>
  );
}
