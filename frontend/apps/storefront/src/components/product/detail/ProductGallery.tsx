'use client';

import { useId, useRef, useState } from 'react';
import { CloseIcon } from '@/components/header/HeaderIcons';
import { useDictionary } from '@/components/i18n/LocaleProvider';
import { fill } from '@/lib/i18n/format';
import type { ProductImage, ProductThumbnail } from '@/lib/catalog/types';
import { ProductVideo } from './ProductVideo';

/**
 * 상세 이미지(#60). 고정 4:5 비율 · 각진 모서리. 모바일은 전폭으로 가로로 넘기고(scroll-snap) 아래에 `1 / 6` 을
 * 글자로 보인다(점 금지). PC 는 썸네일 슬라이더 없이 한 장씩 세로로 크게 이어 붙인다.
 * 갤러리가 비면 대표 이미지 하나를 쓴다. 누르면 전체 화면으로 크게 본다(#60) — 네이티브 모달 <dialog> 라
 * ESC 닫기 · 포커스 가두기 · 닫은 뒤 포커스 복귀를 브라우저가 한다.
 *
 * 착용 영상(VIDEO, BE #61)은 같은 4:5 칸에 노출 순서대로 섞여 온다. 포스터를 먼저 보이고 누르면 그 자리에서 재생한다
 * (`ProductVideo`). 크게 보기는 이미지만 넘겨 본다 — `1 / n` 위치 글자는 영상까지 센다.
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
  const list: ProductImage[] =
    images.length > 0 ? images : thumbnail ? [asGalleryImage(thumbnail)] : [];
  // 크게 보기로 넘겨 보는 것은 이미지만이다
  const zoomable = list.filter((media) => media.mediaType !== 'VIDEO');
  const dialogRef = useRef<HTMLDialogElement>(null);
  const [current, setCurrent] = useState(0);
  // 모바일 스와이프 위치 — 스크롤한 만큼 몇 번째 장인지 센다
  const [visible, setVisible] = useState(0);
  const titleId = useId();
  const t = useDictionary().product;

  if (list.length === 0) {
    return (
      <div className="flex aspect-[4/5] items-center justify-center bg-muted text-sm text-muted-fg">
        {t.imagePending}
      </div>
    );
  }

  const altOf = (image: ProductThumbnail, index: number) => image.altText ?? `${name} ${index + 1}`;
  const open = (media: ProductImage) => {
    setCurrent(Math.max(0, zoomable.indexOf(media)));
    dialogRef.current?.showModal();
  };
  const move = (step: number) =>
    setCurrent((index) => (index + step + zoomable.length) % zoomable.length);
  const shown = zoomable[current] ?? zoomable[0];

  return (
    <>
      <ul
        aria-label={fill(t.gallery.images, { name, count: list.length })}
        onScroll={(event) => {
          const { scrollLeft, clientWidth } = event.currentTarget;
          if (clientWidth > 0) setVisible(Math.round(scrollLeft / clientWidth));
        }}
        // sr-only 버튼 글자가 가로 스크롤 밖으로 새지 않게 relative (가로 스와이프 목록 공통)
        className="relative -mx-4 flex snap-x snap-mandatory overflow-x-auto lg:mx-0 lg:flex-col lg:gap-2 lg:overflow-visible"
      >
        {list.map((image, index) => (
          <li key={image.imageId} className="w-full shrink-0 snap-start lg:w-auto">
            {image.mediaType === 'VIDEO' ? (
              <ProductVideo video={image} label={altOf(image, index)} eager={index === 0} />
            ) : (
              <button
                type="button"
                onClick={() => open(image)}
                aria-label={fill(t.gallery.zoom, { alt: altOf(image, index) })}
                className="block aspect-[4/5] w-full cursor-zoom-in bg-muted focus-visible:outline-offset-[-4px]"
              >
                {/* 이미지 CDN 이 정해지지 않아 next/image 를 아직 쓰지 않는다 (목록과 같다) */}
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img
                  src={image.url}
                  alt=""
                  width={image.width ?? undefined}
                  height={image.height ?? undefined}
                  // 첫 장은 첫 화면에 보이므로 바로 받는다
                  loading={index === 0 ? 'eager' : 'lazy'}
                  fetchPriority={index === 0 ? 'high' : undefined}
                  decoding="async"
                  className="size-full object-cover"
                />
              </button>
            )}
          </li>
        ))}
      </ul>
      {list.length > 1 && (
        // 목록 aria-label 에 장 수가 있어 화면 읽기에는 다시 읽히지 않게 한다
        <p
          aria-hidden
          className="mt-3 text-right text-xs tracking-[0.15em] text-muted-fg lg:hidden"
        >
          {Math.min(visible, list.length - 1) + 1} / {list.length}
        </p>
      )}

      {shown && (
        <dialog
          ref={dialogRef}
          aria-labelledby={titleId}
          onKeyDown={(event) => {
            if (zoomable.length < 2) return;
            if (event.key === 'ArrowRight') move(1);
            if (event.key === 'ArrowLeft') move(-1);
          }}
          className="m-0 h-dvh max-h-none w-full max-w-none bg-bg text-fg backdrop:bg-fg/60"
        >
          <div className="flex h-full flex-col">
            <div className="flex h-14 shrink-0 items-center justify-between px-4">
              <p id={titleId} className="text-xs tracking-[0.15em]" aria-live="polite">
                <span className="sr-only">{fill(t.gallery.zoomTitle, { name })} </span>
                {current + 1} / {zoomable.length}
              </p>
              <button
                type="button"
                aria-label={t.gallery.closeZoom}
                onClick={() => dialogRef.current?.close()}
                className="-mr-2 inline-flex size-10 items-center justify-center"
              >
                <CloseIcon />
              </button>
            </div>

            <div className="relative min-h-0 flex-1">
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img
                src={shown.url}
                alt={altOf(shown, list.indexOf(shown))}
                className="size-full object-contain"
              />
              {zoomable.length > 1 && (
                <>
                  <button
                    type="button"
                    aria-label={t.gallery.prev}
                    onClick={() => move(-1)}
                    className="absolute inset-y-0 left-0 w-1/4 text-2xl opacity-0 hover:opacity-60 focus-visible:opacity-60"
                  >
                    ‹
                  </button>
                  <button
                    type="button"
                    aria-label={t.gallery.next}
                    onClick={() => move(1)}
                    className="absolute inset-y-0 right-0 w-1/4 text-2xl opacity-0 hover:opacity-60 focus-visible:opacity-60"
                  >
                    ›
                  </button>
                </>
              )}
            </div>
          </div>
        </dialog>
      )}
    </>
  );
}

/** 갤러리가 비었을 때 쓰는 대표 이미지를 갤러리 한 칸 모양으로 */
function asGalleryImage(thumbnail: ProductThumbnail): ProductImage {
  return { ...thumbnail, mediaType: 'IMAGE', posterUrl: null, displayOrder: 0 };
}
