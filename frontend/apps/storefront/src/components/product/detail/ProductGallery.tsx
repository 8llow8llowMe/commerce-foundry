'use client';

import { useId, useRef, useState } from 'react';
import { CloseIcon } from '@/components/header/HeaderIcons';
import { useDictionary } from '@/components/i18n/LocaleProvider';
import { fill } from '@/lib/i18n/format';
import type { ProductImage, ProductThumbnail } from '@/lib/catalog/types';

/**
 * 상세 이미지. 고정 3:4 비율 · 각진 모서리. 모바일은 가로로 넘기고(scroll-snap), PC 는 2열로 쌓는다.
 * 갤러리가 비면 대표 이미지 하나를 쓴다. 누르면 전체 화면으로 크게 본다(#60) — 네이티브 모달 <dialog> 라
 * ESC 닫기 · 포커스 가두기 · 닫은 뒤 포커스 복귀를 브라우저가 한다. 착용 영상은 API 가 생기면 더한다.
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
  const dialogRef = useRef<HTMLDialogElement>(null);
  const [current, setCurrent] = useState(0);
  const titleId = useId();
  const t = useDictionary().product;

  if (list.length === 0) {
    return (
      <div className="flex aspect-[3/4] items-center justify-center bg-muted text-sm text-muted-fg">
        {t.imagePending}
      </div>
    );
  }

  const altOf = (image: ProductThumbnail, index: number) => image.altText ?? `${name} ${index + 1}`;
  const open = (index: number) => {
    setCurrent(index);
    dialogRef.current?.showModal();
  };
  const move = (step: number) => setCurrent((index) => (index + step + list.length) % list.length);
  const shown = list[current] ?? list[0]!;

  return (
    <>
      <ul
        aria-label={fill(t.gallery.images, { name, count: list.length })}
        className="-mx-4 flex snap-x snap-mandatory overflow-x-auto lg:mx-0 lg:grid lg:grid-cols-2 lg:gap-2 lg:overflow-visible"
      >
        {list.map((image, index) => (
          <li key={image.imageId} className="w-full shrink-0 snap-start lg:w-auto">
            <button
              type="button"
              onClick={() => open(index)}
              aria-label={fill(t.gallery.zoom, { alt: altOf(image, index) })}
              className="block aspect-[3/4] w-full cursor-zoom-in bg-muted focus-visible:outline-offset-[-4px]"
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
          </li>
        ))}
      </ul>

      <dialog
        ref={dialogRef}
        aria-labelledby={titleId}
        onKeyDown={(event) => {
          if (list.length < 2) return;
          if (event.key === 'ArrowRight') move(1);
          if (event.key === 'ArrowLeft') move(-1);
        }}
        className="m-0 h-dvh max-h-none w-full max-w-none bg-bg text-fg backdrop:bg-fg/60"
      >
        <div className="flex h-full flex-col">
          <div className="flex h-14 shrink-0 items-center justify-between px-4">
            <p id={titleId} className="text-xs tracking-[0.15em]" aria-live="polite">
              <span className="sr-only">{fill(t.gallery.zoomTitle, { name })} </span>
              {current + 1} / {list.length}
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
            <img src={shown.url} alt={altOf(shown, current)} className="size-full object-contain" />
            {list.length > 1 && (
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
    </>
  );
}
