import type { ProductImage, ProductThumbnail } from '@/lib/catalog/types';

/**
 * 상세 이미지. 고정 3:4 비율 · 각진 모서리. 모바일은 가로로 넘기고(scroll-snap), PC 는 2열로 쌓는다.
 * 갤러리가 비면 대표 이미지 하나를 쓴다. 확대 보기 · 착용 영상은 #60 에서 더한다.
 */
export function ProductGallery({
  name,
  thumbnail,
  images,
}: {
  name: string;
  thumbnail: ProductThumbnail | null;
  images: ProductImage[];
}) {
  const list: ProductThumbnail[] = images.length > 0 ? images : thumbnail ? [thumbnail] : [];

  if (list.length === 0) {
    return (
      <div className="flex aspect-[3/4] items-center justify-center bg-muted text-sm text-muted-fg">
        이미지 준비 중
      </div>
    );
  }

  return (
    <ul
      aria-label={`${name} 이미지 ${list.length}장`}
      className="-mx-4 flex snap-x snap-mandatory overflow-x-auto lg:mx-0 lg:grid lg:grid-cols-2 lg:gap-2 lg:overflow-visible"
    >
      {list.map((image, index) => (
        <li key={image.imageId} className="w-full shrink-0 snap-start lg:w-auto">
          <div className="aspect-[3/4] bg-muted">
            {/* 이미지 CDN 이 정해지지 않아 next/image 를 아직 쓰지 않는다 (목록과 같다) */}
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img
              src={image.url}
              alt={image.altText ?? `${name} ${index + 1}`}
              width={image.width ?? undefined}
              height={image.height ?? undefined}
              // 첫 장은 첫 화면에 보이므로 바로 받는다
              loading={index === 0 ? 'eager' : 'lazy'}
              fetchPriority={index === 0 ? 'high' : undefined}
              decoding="async"
              className="size-full object-cover"
            />
          </div>
        </li>
      ))}
    </ul>
  );
}
