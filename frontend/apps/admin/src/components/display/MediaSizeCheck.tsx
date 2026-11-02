'use client';

import { useCallback, useState, type SyntheticEvent } from 'react';
import { mediaSizeNotices, type MediaSpec } from '@/lib/display/media-spec';

type Measured = { src: string; width: number; height: number };

/**
 * 썸네일 `<img>` 가 불러온 원본 크기를 잰다(#71 해상도 · 비율 안내). 돌려준 `onLoad` 를 그 `<img>` 에 단다.
 * 이 썸네일들은 화면이 데이터를 받은 뒤 클라이언트에서 그려 load 이벤트를 놓치지 않는다(서버 렌더 · hydration 이 아니다).
 * 주소가 바뀌면 옛 크기는 쓰지 않는다.
 */
export function useImageSize(src: string | null) {
  const [measured, setMeasured] = useState<Measured | null>(null);
  const onLoad = useCallback(
    (event: SyntheticEvent<HTMLImageElement>) => {
      const img = event.currentTarget;
      if (!src || img.naturalWidth === 0) return;
      setMeasured({ src, width: img.naturalWidth, height: img.naturalHeight });
    },
    [src],
  );
  return { size: measured && measured.src === src ? measured : null, onLoad };
}

/** 잰 크기가 권장과 다르면 안내한다(저장은 막지 않는다) */
export function MediaSizeNotice({
  spec,
  size,
  label,
}: {
  spec: MediaSpec | null;
  size: { width: number; height: number } | null;
  /** 칸이 둘일 때 앞에 붙인다("PC") */
  label?: string;
}) {
  if (!spec || !size) return null;
  const notices = mediaSizeNotices(spec, size.width, size.height);
  if (notices.length === 0) return null;
  return (
    <ul className="space-y-0.5 text-xs text-fg">
      {notices.map((notice) => (
        <li key={notice}>
          {label ? `${label} — ` : ''}
          {notice}
        </li>
      ))}
    </ul>
  );
}
