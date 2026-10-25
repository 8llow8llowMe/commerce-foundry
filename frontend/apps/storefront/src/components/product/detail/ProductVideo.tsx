'use client';

import { useEffect, useRef, useState } from 'react';
import { useDictionary } from '@/components/i18n/LocaleProvider';
import type { ProductImage } from '@/lib/catalog/types';
import { fill } from '@/lib/i18n/format';

/**
 * 상세 갤러리의 착용 영상 한 칸(#60, BE #61). 갤러리와 같은 4:5 칸에서 포스터를 먼저 보이고, 누르면 그 자리에서 재생한다.
 * 자동재생하지 않고 소리는 끈 채(`muted`) 시작한다 — 소리 · 일시정지는 브라우저 기본 컨트롤로 사용자가 정한다.
 * iOS 가 전체 화면으로 띄우지 않게 `playsInline` 을 붙인다. 영상 URL 은 BE 가 우리 스토리지 URL 만 내려 화면에서 거르지 않는다.
 */
export function ProductVideo({
  video,
  label,
  eager = false,
}: {
  video: ProductImage;
  /** 대체 텍스트(없으면 '상품명 n') */
  label: string;
  /** 첫 칸이면 포스터를 바로 받는다 */
  eager?: boolean;
}) {
  const [playing, setPlaying] = useState(false);
  if (playing) return <VideoPlayer video={video} label={label} />;
  return (
    <VideoPoster
      posterUrl={video.posterUrl}
      label={label}
      eager={eager}
      onPlay={() => setPlaying(true)}
    />
  );
}

/** 재생 전 — 포스터와 재생 표시. 영상 파일은 아직 받지 않는다 */
export function VideoPoster({
  posterUrl,
  label,
  eager,
  onPlay,
}: {
  posterUrl: string | null;
  label: string;
  eager: boolean;
  onPlay: () => void;
}) {
  const t = useDictionary().product.gallery;
  return (
    <button
      type="button"
      onClick={onPlay}
      aria-label={fill(t.play, { alt: label })}
      className="relative block aspect-[4/5] w-full bg-muted focus-visible:outline-offset-[-4px]"
    >
      {posterUrl && (
        // eslint-disable-next-line @next/next/no-img-element
        <img
          src={posterUrl}
          alt=""
          loading={eager ? 'eager' : 'lazy'}
          fetchPriority={eager ? 'high' : undefined}
          decoding="async"
          className="size-full object-cover"
        />
      )}
      <span aria-hidden className="absolute inset-0 flex items-center justify-center">
        <span className="flex size-14 items-center justify-center bg-bg/90 text-fg">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" focusable="false">
            <path d="M7 4.5v15l12.5-7.5z" />
          </svg>
        </span>
      </span>
    </button>
  );
}

/**
 * 재생 중 — 사용자가 누른 뒤에만 그린다. 그려지면 한 번 재생을 시작하고(누름에 대한 응답이라 자동재생이 아니다),
 * 움직임 줄이기 설정이면 반복하지 않는다. 가로 스와이프로 화면 밖에 나가면 멈춘다.
 */
export function VideoPlayer({ video, label }: { video: ProductImage; label: string }) {
  const t = useDictionary().product.gallery;
  const ref = useRef<HTMLVideoElement>(null);

  useEffect(() => {
    const element = ref.current;
    if (!element) return;
    element.loop = !window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    // 브라우저가 막으면 컨트롤이 보이므로 사용자가 다시 누를 수 있다
    element.play().catch(() => {});
    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry && entry.intersectionRatio < 0.5) element.pause();
      },
      { threshold: 0.5 },
    );
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  return (
    <video
      ref={ref}
      src={video.url}
      poster={video.posterUrl ?? undefined}
      aria-label={fill(t.video, { alt: label })}
      controls
      muted
      playsInline
      preload="metadata"
      className="block aspect-[4/5] w-full bg-muted object-cover"
    />
  );
}
