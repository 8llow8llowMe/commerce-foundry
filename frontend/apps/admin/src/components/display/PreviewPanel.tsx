'use client';

import { Button, cx } from '@cf/ui';
import { useEffect, useRef, useState } from 'react';
import {
  PREVIEW_DRAFT,
  PREVIEW_PATH,
  isPreviewReady,
  PREVIEW_WIDTHS,
  previewScale,
  type PreviewDevice,
  type PreviewDraft,
  type PreviewSection,
} from '@/lib/display/preview';

/** 미리보기 칸의 보이는 높이(px) — 1440 폭은 줄인 만큼 iframe 을 길게 한다 */
const VIEW_HEIGHT = 720;
/** 입력이 멈춘 뒤 보낼 때까지(ms) — 한 글자마다 서버에서 다시 그리지 않게 */
const SEND_DELAY = 300;

const DEVICE_LABELS: Record<PreviewDevice, string> = { mobile: '모바일', desktop: 'PC' };

/**
 * 메인 전시 미리보기(#71) — 스토어프론트 `/preview/main` 을 iframe 으로 열고 저장 전 편집 상태를 postMessage 로 보낸다.
 * iframe 이 준비됐다고 알리면(origin · source 확인) 그때부터 바뀔 때마다 보낸다. 받는 쪽도 이 origin 만 받는다.
 * PC 폭(1440)은 칸에 맞춰 줄여 보인다. 저장하지 않으므로 실제 메인은 바뀌지 않는다.
 */
export function PreviewPanel({
  origin,
  sections,
  onClose,
}: {
  /** 스토어프론트 origin */
  origin: string;
  sections: PreviewSection[];
  onClose: () => void;
}) {
  const [device, setDevice] = useState<PreviewDevice>('mobile');
  const [boxWidth, setBoxWidth] = useState(0);
  const [reloadKey, setReloadKey] = useState(0);
  const frameRef = useRef<HTMLIFrameElement>(null);
  const boxRef = useRef<HTMLDivElement>(null);

  // 보낼 때는 늘 최신 편집 상태를 쓴다
  const sectionsRef = useRef(sections);
  useEffect(() => {
    sectionsRef.current = sections;
  });
  // iframe 이 준비를 알렸는가 — 새로 고침 · iframe 안 이동 뒤 돌아오면 다시 알린다
  const ready = useRef(false);

  useEffect(() => {
    const send = () => {
      const draft: PreviewDraft = { type: PREVIEW_DRAFT, sections: sectionsRef.current };
      frameRef.current?.contentWindow?.postMessage(draft, origin);
    };
    const onMessage = (event: MessageEvent) => {
      if (!isPreviewReady(event, origin, frameRef.current?.contentWindow ?? null)) return;
      // 준비를 알릴 때마다 바로 보낸다(iframe 이 다시 열렸으면 앞서 보낸 상태가 없다)
      ready.current = true;
      send();
    };
    window.addEventListener('message', onMessage);
    return () => window.removeEventListener('message', onMessage);
  }, [origin]);

  // 그다음은 입력이 멈추면 보낸다
  useEffect(() => {
    if (!ready.current) return;
    const timer = window.setTimeout(() => {
      const draft: PreviewDraft = { type: PREVIEW_DRAFT, sections };
      frameRef.current?.contentWindow?.postMessage(draft, origin);
    }, SEND_DELAY);
    return () => window.clearTimeout(timer);
  }, [sections, origin]);

  useEffect(() => {
    const box = boxRef.current;
    if (!box) return;
    const observer = new ResizeObserver(([entry]) => setBoxWidth(entry?.contentRect.width ?? 0));
    observer.observe(box);
    return () => observer.disconnect();
  }, []);

  const width = PREVIEW_WIDTHS[device];
  const scale = previewScale(width, boxWidth);

  return (
    <section aria-labelledby="display-preview-heading" className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2">
        <h2 id="display-preview-heading" className="mr-auto font-semibold">
          미리보기
        </h2>
        <div role="group" aria-label="보는 화면 폭" className="flex gap-1">
          {(Object.keys(PREVIEW_WIDTHS) as PreviewDevice[]).map((key) => (
            <Button
              key={key}
              variant={device === key ? 'primary' : 'secondary'}
              aria-pressed={device === key}
              onClick={() => setDevice(key)}
            >
              {DEVICE_LABELS[key]} {PREVIEW_WIDTHS[key]}px
            </Button>
          ))}
        </div>
        <Button
          variant="secondary"
          onClick={() => {
            ready.current = false;
            setReloadKey((n) => n + 1);
          }}
        >
          새로 고침
        </Button>
        <Button variant="secondary" onClick={onClose}>
          닫기
        </Button>
      </div>
      <p className="text-xs text-muted-fg">
        저장하지 않은 순서 · 문구 · 상품이 그대로 보입니다. 저장하기 전에는 실제 메인이 바뀌지
        않습니다. 숨김이거나 노출 기간 밖인 섹션은 메인처럼 미리보기에도 보이지 않습니다. 상품 가격
        · 사진은 실제 메인과 조금 다를 수 있습니다.
        {scale < 1 && ` PC 화면은 ${Math.round(scale * 100)}% 로 줄여 보입니다.`}
      </p>
      <div
        ref={boxRef}
        className="overflow-hidden rounded-md border border-border bg-muted"
        style={{ height: VIEW_HEIGHT }}
      >
        <iframe
          key={reloadKey}
          ref={frameRef}
          src={`${origin}${PREVIEW_PATH}`}
          title={`메인 미리보기 — ${DEVICE_LABELS[device]} ${width}px`}
          // 미리보기 안 링크가 어드민 창을 옮기지 못하게(top navigation 미허용)
          sandbox="allow-scripts allow-same-origin"
          className={cx('block origin-top-left bg-bg', device === 'mobile' && 'mx-auto')}
          style={{
            width,
            height: VIEW_HEIGHT / scale,
            transform: scale < 1 ? `scale(${scale})` : undefined,
          }}
        />
      </div>
    </section>
  );
}
