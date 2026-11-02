'use client';

import { FieldError } from '@cf/ui';
import { useCallback, useEffect, useId, useRef, useState, type ReactNode } from 'react';
import type { AccessDenied } from '@/lib/api-errors';
import {
  formatBytes,
  IMAGE_ACCEPT,
  IMAGE_MAX_BYTES,
  UPLOAD_UNAVAILABLE,
  uploadImage,
} from '@/lib/upload/image-upload';

/** 올린 이미지 한 장. 미리보기는 이 브라우저의 파일(`file`)로 칸이 그린다 — `uploads/` 는 공개 읽기가 닫혀 있어 서버 주소가 없다 */
export type UploadedImage = {
  uploadKey: string;
  file: File;
};

export type ImageUploadFieldProps = {
  label: string;
  /** 업로드 자리 발급 경로(BFF) — 예: `PRODUCT_IMAGE_UPLOAD_PATH` */
  issuePath: string;
  value: UploadedImage | null;
  onChange: (value: UploadedImage | null) => void;
  /** 올리는 중인지 — 부르는 화면은 올리는 동안 저장을 막는다(키 없이 저장되면 이미지가 조용히 빠진다) */
  onUploadingChange?: (uploading: boolean) => void;
  /** 403 — 화면이 `AccessNotice` 를 그린다 */
  onAccessDenied?: (denied: AccessDenied) => void;
  /** 권장 비율 · 해상도 같은 안내 */
  hint?: ReactNode;
  /** 서버 검증 오류(저장 응답의 fieldErrors) */
  errors?: readonly string[];
  disabled?: boolean;
  /** 꺼 둔 이유 — `disabled` 일 때 안내로 보인다 */
  disabledNotice?: string;
};

const DEFAULT_HINT = `JPG · PNG · WebP, ${formatBytes(IMAGE_MAX_BYTES)} 이하`;

/**
 * 이미지 한 장 업로드 칸(#29). 파일을 고르면 바로 검사 → 발급 → 스토리지 업로드까지 하고 `{ uploadKey, file }` 을 `onChange` 로 넘긴다.
 * 저장(상품 · 전시)은 부르는 화면이 한다 — 올린 파일은 저장하지 않으면 하루 뒤 지워진다.
 * 미리보기 object URL 은 이 칸이 `value.file` 로 만들고 놓는다 — `<img>` 의 callback ref 가 만들고 그 정리 함수가 놓아(React 19),
 * 다시 마운트(StrictMode 포함)돼도 만들기 · 놓기가 짝이 맞는다.
 * 칸이 사라지거나 다른 파일을 고르면 진행 중인 업로드를 취소한다.
 */
export function ImageUploadField({
  label,
  issuePath,
  value,
  onChange,
  onUploadingChange,
  onAccessDenied,
  hint = DEFAULT_HINT,
  errors = [],
  disabled = false,
  disabledNotice,
}: ImageUploadFieldProps) {
  const id = useId();
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  const file = value?.file ?? null;
  const preview = useCallback(
    (img: HTMLImageElement | null) => {
      if (!img || !file) return;
      const url = URL.createObjectURL(file);
      img.src = url;
      return () => URL.revokeObjectURL(url);
    },
    [file],
  );

  useEffect(() => () => abortRef.current?.abort(), []);

  const setBusy = (busy: boolean) => {
    setUploading(busy);
    onUploadingChange?.(busy);
  };

  const pick = async (picked: File) => {
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    setBusy(true);
    setError(null);
    try {
      const result = await uploadImage(picked, { issuePath, signal: controller.signal });
      if (controller.signal.aborted) return;
      if (result.ok) onChange({ uploadKey: result.uploadKey, file: picked });
      else if (result.kind === 'access') onAccessDenied?.(result.denied);
      else setError(result.message);
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
  const messages = error ? [error, ...errors] : errors;
  const describedBy = [
    `${id}-hint`,
    disabled && disabledNotice ? `${id}-off` : null,
    messages.length > 0 ? `${id}-error` : null,
  ]
    .filter(Boolean)
    .join(' ');

  return (
    <div className="flex flex-col gap-2">
      <label htmlFor={`${id}-file`} className="text-sm font-medium">
        {label}
      </label>
      {value && (
        <div className="flex items-center gap-3">
          {/* 고른 파일의 로컬 미리보기라 next/image 최적화 대상이 아니다. src 는 ref 가 단다 */}
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img
            ref={preview}
            alt=""
            className="h-24 w-24 rounded-md border border-border bg-muted object-cover"
          />
          <div className="flex flex-col gap-1 text-sm">
            <span className="break-all">{value.file.name}</span>
            <span className="text-xs text-muted-fg">
              {formatBytes(value.file.size)} · 올림 완료
            </span>
            <button
              type="button"
              disabled={off}
              aria-label={`${label.replace(/\s*\([^)]*\)$/, '')} 빼기`}
              onClick={() => onChange(null)}
              className="w-fit text-sm text-muted-fg underline underline-offset-4 disabled:opacity-50"
            >
              빼기
            </button>
          </div>
        </div>
      )}
      <input
        id={`${id}-file`}
        type="file"
        accept={IMAGE_ACCEPT}
        disabled={off}
        aria-describedby={describedBy}
        aria-invalid={messages.length > 0 || undefined}
        onChange={(event) => {
          const picked = event.currentTarget.files?.[0];
          // 같은 파일을 다시 고를 수 있게 비운다
          event.currentTarget.value = '';
          if (picked) void pick(picked);
        }}
        className="w-fit text-sm file:mr-3 file:rounded-md file:border file:border-border file:bg-bg file:px-3 file:py-1.5 file:text-sm disabled:opacity-50"
      />
      {/* 올리는 중을 화면 낭독기에도 알린다(완료는 파일 이름 줄, 실패는 오류의 role="alert") */}
      <p id={`${id}-hint`} aria-live="polite" className="text-xs text-muted-fg">
        {uploading ? '올리는 중…' : hint}
      </p>
      {disabled && disabledNotice && (
        <p id={`${id}-off`} className="text-xs text-muted-fg">
          {disabledNotice}
        </p>
      )}
      <FieldError id={`${id}-error`} messages={messages} />
    </div>
  );
}
