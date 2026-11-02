'use client';

import { FieldError } from '@cf/ui';
import { useId } from 'react';
import { DESCRIPTION_MAX } from '@/lib/product/create';

/** 상품 상세 설명 HTML 입력(등록 · 기본 정보 수정). 저장할 때 서버가 허용 목록으로 정제한다 — 화면은 글자 수만 본다 */
export function DescriptionHtmlField({
  value,
  onChange,
  errors,
}: {
  value: string;
  onChange: (value: string) => void;
  errors: readonly string[];
}) {
  const id = useId();
  const invalid = errors.length > 0;
  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={`${id}-description`} className="text-sm font-medium">
        상세 설명 HTML (선택)
      </label>
      <textarea
        id={`${id}-description`}
        value={value}
        rows={10}
        aria-invalid={invalid || undefined}
        aria-describedby={`${id}-description-help${invalid ? ` ${id}-description-error` : ''}`}
        onChange={(event) => onChange(event.currentTarget.value)}
        className="rounded-md border border-border bg-bg px-3 py-2 font-mono text-sm text-fg outline-none focus:ring-2 focus:ring-primary"
      />
      <p id={`${id}-description-help`} className="text-xs text-muted-fg">
        {value.length.toLocaleString('ko-KR')} / {DESCRIPTION_MAX.toLocaleString('ko-KR')}자.
        허용되지 않은 태그 · 속성 · 상대 주소는 저장할 때 빠집니다.
      </p>
      <FieldError id={`${id}-description-error`} messages={errors} />
    </div>
  );
}
