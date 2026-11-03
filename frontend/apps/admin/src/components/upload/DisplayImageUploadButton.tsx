'use client';

import { Button, FieldError } from '@cf/ui';
import { useEffect, useId, useRef, useState } from 'react';
import type { AccessDenied } from '@/lib/api-errors';
import {
  confirmDisplayImage,
  formatBytes,
  IMAGE_ACCEPT,
  IMAGE_MAX_BYTES,
  UPLOAD_UNAVAILABLE,
  uploadDisplayImage,
  type DisplayImageResult,
} from '@/lib/upload/image-upload';

export type DisplayImageUploadButtonProps = {
  /** 어느 칸에 올리는지 — 파일 고르기 칸의 이름이 된다(예: "1번째 이미지 PC 파일 올리기") */
  label: string;
  /** 확정까지 끝난 공개 주소 — 부르는 화면이 주소 칸에 넣는다 */
  onUploaded: (publicUrl: string) => void;
  /** 올리는(확정 포함) 중인지 — 부르는 화면은 그동안 저장을 막는다(주소 없이 저장되면 이미지가 빠진다) */
  onUploadingChange?: (uploading: boolean) => void;
  /** 403 — 화면이 `AccessNotice` 를 그린다 */
  onAccessDenied?: (denied: AccessDenied) => void;
  disabled?: boolean;
};

/**
 * 전시 이미지 파일 올리기(#71 · BE #508). 고르면 곧바로 검사 → 발급 → 스토리지 업로드 → 확정까지 하고 공개 주소를 `onUploaded` 로 넘긴다.
 * 미리보기 · 권장 비율 안내는 주소 칸 쪽(공개 주소로 그린다)이 맡는다 — 이 칸은 올리기와 실패 안내만 한다.
 * 확정만 실패하면(결과 모름 · 409 · 401) 파일을 다시 고르지 않고 "다시 확인"으로 같은 키를 다시 확정한다. 키는 화면에 보이지 않는다.
 * 칸이 사라지거나 다른 파일을 고르면 진행 중인 업로드를 취소한다.
 */
export function DisplayImageUploadButton({
  label,
  onUploaded,
  onUploadingChange,
  onAccessDenied,
  disabled = false,
}: DisplayImageUploadButtonProps) {
  const id = useId();
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [retryKey, setRetryKey] = useState<string | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  const busyRef = useRef(false);
  // 끝난 뒤의 콜백은 그때의 것을 부른다(올리는 동안 부모가 다시 그려도 옛 칸을 고치지 않게)
  const latest = useRef({ onUploaded, onUploadingChange, onAccessDenied });
  useEffect(() => {
    latest.current = { onUploaded, onUploadingChange, onAccessDenied };
  });

  const setBusy = (busy: boolean) => {
    setUploading(busy);
    // 바뀔 때만 알린다 — 부르는 화면이 올리는 칸 수를 센다
    if (busyRef.current === busy) return;
    busyRef.current = busy;
    latest.current.onUploadingChange?.(busy);
  };

  useEffect(
    () => () => {
      abortRef.current?.abort();
      if (busyRef.current) {
        busyRef.current = false;
        latest.current.onUploadingChange?.(false);
      }
    },
    [],
  );

  const run = async (task: (signal: AbortSignal) => Promise<DisplayImageResult>) => {
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    setBusy(true);
    setError(null);
    setRetryKey(null);
    try {
      const result = await task(controller.signal);
      if (controller.signal.aborted) return;
      if (result.ok) latest.current.onUploaded(result.publicUrl);
      else if (result.kind === 'access') latest.current.onAccessDenied?.(result.denied);
      else {
        setError(result.message);
        setRetryKey(result.retryKey ?? null);
      }
    } catch {
      // 취소는 안내하지 않는다. 그 밖(예상 못 한 예외)은 일시 오류로 보인다
      if (!controller.signal.aborted) setError(UPLOAD_UNAVAILABLE);
    } finally {
      if (abortRef.current === controller) {
        abortRef.current = null;
        setBusy(false);
      }
    }
  };

  const off = disabled || uploading;
  const describedBy = [`${id}-hint`, error ? `${id}-error` : null].filter(Boolean).join(' ');

  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={`${id}-file`} className="text-xs text-muted-fg">
        {label}
      </label>
      <input
        id={`${id}-file`}
        type="file"
        accept={IMAGE_ACCEPT}
        disabled={off}
        aria-describedby={describedBy}
        aria-invalid={error ? true : undefined}
        onChange={(event) => {
          const picked = event.currentTarget.files?.[0];
          // 같은 파일을 다시 고를 수 있게 비운다
          event.currentTarget.value = '';
          if (picked) void run((signal) => uploadDisplayImage(picked, { signal }));
        }}
        className="w-fit text-sm file:mr-3 file:rounded-md file:border file:border-border file:bg-bg file:px-3 file:py-1.5 file:text-sm disabled:opacity-50"
      />
      {/* 올리는 중을 화면 낭독기에도 알린다(완료는 주소 칸 · 미리보기, 실패는 오류의 role="alert") */}
      <p id={`${id}-hint`} aria-live="polite" className="text-xs text-muted-fg">
        {uploading
          ? '올리는 중…'
          : `JPG · PNG · WebP, ${formatBytes(IMAGE_MAX_BYTES)} 이하. 올리면 주소 칸이 채워집니다.`}
      </p>
      <FieldError id={`${id}-error`} messages={error ? [error] : []} />
      {retryKey && !uploading && (
        <div>
          <Button
            variant="secondary"
            disabled={disabled}
            onClick={() => void run((signal) => confirmDisplayImage(retryKey, { signal }))}
          >
            다시 확인
          </Button>
        </div>
      )}
    </div>
  );
}
