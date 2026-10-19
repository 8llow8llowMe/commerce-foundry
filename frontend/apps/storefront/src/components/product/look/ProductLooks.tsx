import { cx } from '@cf/ui';
import { fetchLookProducts, fetchProductLooks } from '@/lib/catalog/look-products';
import type { ProductThumbnail } from '@/lib/catalog/types';
import { LookPicker } from './LookPicker';

function LookImage({ image, title }: { image: ProductThumbnail | null; title: string }) {
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
 * 상품 상세 SHOP THE LOOK — 이 상품이 들어간 룩(#62)마다 착장 화보 + 구성품 함께 담기.
 * 룩이 없거나 구성품을 하나도 못 받으면 영역을 그리지 않는다.
 * 룩 하나면 화보 | 구성품 두 칸, 여럿이면 PC 3열 · 모바일 가로 스와이프(72vw)로 늘어놓는다.
 */
export async function ProductLooks({ productId }: { productId: string }) {
  const looks = await fetchProductLooks(productId);
  if (looks.length === 0) return null;

  const products = await fetchLookProducts(
    looks.flatMap((look) => look.items.map((item) => item.productId)),
  );
  const visible = looks
    .map((look) => ({
      ...look,
      products: look.items.flatMap((item) => products.get(item.productId) ?? []),
    }))
    .filter((look) => look.products.length > 0);
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
            : '-mx-4 flex snap-x snap-mandatory scroll-px-4 gap-6 overflow-x-auto px-4 lg:mx-0 lg:grid lg:grid-cols-3 lg:overflow-visible lg:px-0',
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
                {look.description && <p className="leading-7">{look.description}</p>}
              </div>
              <LookPicker products={look.products} currentProductId={productId} />
            </div>
          </li>
        ))}
      </ul>
    </section>
  );
}
