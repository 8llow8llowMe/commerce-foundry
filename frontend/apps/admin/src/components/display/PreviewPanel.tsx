'use client';

import { apiFetch } from '@cf/api-client';
import { Button, cx } from '@cf/ui';
import { useEffect, useId, useRef, useState } from 'react';
import type { PreviewFailure } from '@/lib/display/errors';
import {
  createPreviewLoader,
  createPreviewSession,
  PREVIEW_PATH,
  isPreviewReady,
  PREVIEW_WIDTHS,
  previewScale,
  type PreviewDevice,
  type PreviewSection,
} from '@/lib/display/preview';
import { DISPLAY_PREVIEW_PATH } from '@/lib/display/types';

/** 미리보기 칸의 보이는 높이(px) — 1440 폭은 줄인 만큼 iframe 을 길게 한다 */
const VIEW_HEIGHT = 720;
/** 입력이 멈춘 뒤 미리보기를 부를 때까지(ms) — 한 글자마다 BE 미리보기를 부르지 않게 */
const SEND_DELAY = 300;

const DEVICE_LABELS: Record<PreviewDevice, string> = { mobile: '모바일', desktop: 'PC' };

/**
 * 메인 전시 미리보기(#71) — 저장 전 편집 상태가 바뀔 때마다(300ms 묶음) BE 미리보기(#340)를 어드민 BFF 로 부르고,
 * 받은 응답을 스토어프론트 `/preview/main`(iframe)에 postMessage 로 보낸다. 늦게 온 앞 응답은 버린다(`createPreviewLoader`).
 * iframe 이 준비됐다고 알리면(origin · source 확인) 마지막으로 받은 응답을 바로 보낸다. 받는 쪽도 이 origin 만 받는다.
 * 실패하면 마지막으로 그린 미리보기를 그대로 두고 패널에 안내한다(입력 오류는 섹션 위치까지).
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
  const frameId = useId();
  const boxRef = useRef<HTMLDivElement>(null);

  const [failure, setFailure] = useState<PreviewFailure | null>(null);
  // 429 면 "다시 시도" 를 `Retry-After` 동안 막는다
  const [coolingDown, setCoolingDown] = useState(false);
  const [session] = useState(() =>
    createPreviewSession({
      loader: createPreviewLoader((body, signal) =>
        apiFetch<unknown>(DISPLAY_PREVIEW_PATH, { method: 'POST', json: body, signal }),
      ),
      // ref 를 렌더 중에 넘기지 않으려고 보낼 때 iframe 을 id 로 찾는다
      post: (message) =>
        (document.getElementById(frameId) as HTMLIFrameElement | null)?.contentWindow?.postMessage(
          message,
          origin,
        ),
      onFailure: setFailure,
    }),
  );

  useEffect(() => {
    const onMessage = (event: MessageEvent) => {
      if (!isPreviewReady(event, origin, frameRef.current?.contentWindow ?? null)) return;
      // 준비를 알릴 때마다 마지막 응답을 바로 보낸다(iframe 이 다시 열렸으면 앞서 보낸 응답이 없다)
      session.ready();
    };
    window.addEventListener('message', onMessage);
    return () => window.removeEventListener('message', onMessage);
  }, [origin, session]);

  // 편집 상태가 바뀌면 입력이 멈춘 뒤 미리보기를 부른다
  useEffect(() => {
    const timer = window.setTimeout(() => void session.update(sections), SEND_DELAY);
    return () => window.clearTimeout(timer);
  }, [sections, session]);

  // 닫으면 남은 요청을 버린다
  useEffect(() => () => session.close(), [session]);

  useEffect(() => {
    const seconds = failure?.cooldownSec ?? null;
    if (seconds === null) return;
    const start = window.setTimeout(() => setCoolingDown(true), 0);
    const end = window.setTimeout(() => setCoolingDown(false), seconds * 1000);
    return () => {
      window.clearTimeout(start);
      window.clearTimeout(end);
      setCoolingDown(false);
    };
  }, [failure]);

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
            session.frameReset();
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
        않습니다. 숨김이거나 노출 기간 밖인 섹션 · 숨긴 상품은 메인처럼 미리보기에도 보이지 않고,
        상품 가격 · 사진은 실제 메인과 같은 값입니다.
        {scale < 1 && ` PC 화면은 ${Math.round(scale * 100)}% 로 줄여 보입니다.`}
      </p>
      {failure && (
        <div role="alert" className="flex flex-col gap-2 rounded-md bg-muted px-4 py-3 text-sm">
          <p className="text-danger">{failure.message}</p>
          {failure.details.length > 0 && (
            <ul className="list-disc pl-5 text-danger">
              {failure.details.map((detail) => (
                <li key={detail}>{detail}</li>
              ))}
            </ul>
          )}
          {failure.retry && (
            <div>
              <Button
                variant="secondary"
                disabled={coolingDown}
                onClick={() => void session.retry()}
              >
                다시 시도
              </Button>
            </div>
          )}
        </div>
      )}
      <div
        ref={boxRef}
        className="overflow-hidden rounded-md border border-border bg-muted"
        style={{ height: VIEW_HEIGHT }}
      >
        <iframe
          key={reloadKey}
          ref={frameRef}
          id={frameId}
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
