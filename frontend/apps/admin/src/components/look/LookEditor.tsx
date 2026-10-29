'use client';

import { apiFetch } from '@cf/api-client';
import { Button, Checkbox, FieldError } from '@cf/ui';
import { useEffect, useId, useRef, useState } from 'react';
import { TextInput } from '@/components/display/SectionEditor';
import { ProductPickField } from '@/components/display/ProductPickField';
import type { AccessDenied, FormErrors } from '@/lib/api-errors';
import { normalizeText } from '@/lib/display/text';
import { lookSaveFailure, withSummary } from '@/lib/look/errors';
import { LOOK_LABELS, lookRequest, lookWarnings, type LookForm } from '@/lib/look/form';
import { LOOK_LIMITS, LOOKS_PATH, lookPath, type LookAdmin } from '@/lib/look/types';

/**
 * 룩 만들기 · 고치기 폼(#406). 만들기는 `POST`, 고치기는 `PUT /{lookId}`(전체 교체) — 본문은 같다.
 * 입력 제약은 BE 와 같게 미리 본다(`lookRequest`) — 어기면 보내지 않고 칸에 오류를 붙인다. 정본은 서버다.
 * 부모가 `key` 로 대상을 넘겨, 대상이 바뀌면 폼을 새로 시작한다.
 */
export function LookEditor({
  heading,
  lookId,
  initial,
  onSaved,
  onReload,
  onDenied,
  onCancel,
}: {
  heading: string;
  /** 고칠 룩. null 이면 새로 만든다 */
  lookId: string | null;
  initial: LookForm;
  onSaved: (title: string) => void;
  onReload: (notice: string) => void;
  onDenied: (denied: AccessDenied) => void;
  onCancel: () => void;
}) {
  const id = useId();
  const headingRef = useRef<HTMLHeadingElement>(null);
  useEffect(() => {
    headingRef.current?.focus();
  }, []);
  const [form, setForm] = useState<LookForm>(initial);
  const [errors, setErrors] = useState<FormErrors>({});
  const [saving, setSaving] = useState(false);
  const errorsOf = (key: string) => errors[key] ?? [];
  const set = (patch: Partial<LookForm>) => setForm((current) => ({ ...current, ...patch }));

  const submit = async () => {
    if (saving) return;
    const built = lookRequest(form);
    if (!built.ok) {
      setErrors(withSummary(built.errors));
      return;
    }
    setSaving(true);
    setErrors({});
    try {
      const saved = await apiFetch<LookAdmin>(lookId === null ? LOOKS_PATH : lookPath(lookId), {
        method: lookId === null ? 'POST' : 'PUT',
        json: built.request,
      });
      onSaved(saved.title);
    } catch (cause) {
      setSaving(false);
      const failure = lookSaveFailure(cause);
      if (failure.kind === 'access') onDenied(failure.denied);
      else if (failure.kind === 'reload') onReload(failure.notice);
      else setErrors(failure.errors);
    }
  };

  const warnings = lookWarnings(form);

  return (
    <form
      noValidate
      onSubmit={(event) => {
        event.preventDefault();
        void submit();
      }}
      className="flex flex-col gap-5 rounded-md bg-muted/50 p-5"
      aria-labelledby={`${id}-heading`}
    >
      <h2
        id={`${id}-heading`}
        ref={headingRef}
        tabIndex={-1}
        className="font-semibold outline-none"
      >
        {heading}
      </h2>
      <fieldset disabled={saving} className="flex min-w-0 flex-col gap-5">
        <TextInput
          label={LOOK_LABELS.title}
          value={form.title}
          max={LOOK_LIMITS.title}
          multiline={false}
          hint="상품 상세의 SHOP THE LOOK 에 보이는 룩 이름입니다. 꼭 써야 합니다."
          errors={errorsOf('title')}
          onChange={(title) => set({ title })}
        />
        <TextInput
          label={LOOK_LABELS.description}
          value={form.description}
          max={LOOK_LIMITS.description}
          multiline
          hint="선택입니다. 줄바꿈은 그대로 보입니다."
          errors={errorsOf('description')}
          onChange={(description) => set({ description })}
        />

        <ImageFields
          form={form}
          errorsOf={errorsOf}
          onChange={(patch) => set(patch)}
          onMeasured={(size) =>
            setForm((current) =>
              current.imageUrl.trim() === size.url ? { ...current, imageSize: size } : current,
            )
          }
        />

        <div className="flex flex-col gap-1">
          <Checkbox
            label="고객에게 노출"
            checked={form.visible}
            onChange={(event) => set({ visible: event.currentTarget.checked })}
          />
          <p className="text-xs text-muted-fg">
            끄면 상품 상세에 이 룩이 보이지 않습니다. 룩 기능을 켠 쇼핑몰에서만 고객에게 보입니다.
            {lookId === null && ' 새 룩은 꺼진 채로 시작합니다 — 내용을 확인한 뒤 켜세요.'}
          </p>
        </div>

        <ProductPickField
          max={LOOK_LIMITS.products}
          surface="룩"
          products={form.products}
          errors={errorsOf('products')}
          onChange={(products) => {
            set({ products });
            setErrors((current) => {
              const next = { ...current };
              delete next.products;
              return next;
            });
          }}
        />
      </fieldset>

      {warnings.length > 0 && (
        <ul className="flex flex-col gap-1 rounded-md bg-muted px-4 py-3 text-sm">
          {warnings.map((warning) => (
            <li key={warning}>{warning}</li>
          ))}
        </ul>
      )}
      <FieldError messages={errorsOf('form')} />
      <div className="flex gap-2">
        <Button type="submit" disabled={saving}>
          {saving ? '저장 중…' : '저장'}
        </Button>
        <Button variant="secondary" onClick={onCancel} disabled={saving}>
          취소
        </Button>
      </div>
    </form>
  );
}

/**
 * 대표 이미지 — 업로드(#9)가 아직 없어 쇼핑몰 이미지 저장소에 이미 올린 이미지의 주소를 넣는다. 저장소 주소인지는 서버가 본다(`LOOK_005`).
 * 넣은 주소의 이미지를 작게 보여 주고, 불러오면 원본 크기를 함께 보낸다(고객 화면이 자리를 미리 잡는다).
 */
export function ImageFields({
  form,
  errorsOf,
  onChange,
  onMeasured,
}: {
  form: Pick<LookForm, 'imageUrl' | 'altText'>;
  errorsOf: (key: string) => string[];
  onChange: (patch: Partial<Pick<LookForm, 'imageUrl' | 'altText'>>) => void;
  onMeasured: (size: { url: string; width: number; height: number }) => void;
}) {
  const url = normalizeText(form.imageUrl);
  const [failedUrl, setFailedUrl] = useState<string | null>(null);
  const failed = url !== null && failedUrl === url;
  return (
    <fieldset className="flex min-w-0 flex-col gap-3">
      <legend className="text-sm font-medium">대표 이미지</legend>
      <p className="text-xs text-muted-fg">
        이미지 업로드는 준비 중입니다. 지금은 쇼핑몰 이미지 저장소에 이미 올린 이미지의 주소를 넣어
        주세요. 다른 사이트의 이미지 주소는 저장되지 않습니다.
      </p>
      <div className="flex flex-wrap items-start gap-4">
        <div className="w-28 shrink-0">
          {url && !failed ? (
            // 이미지 CDN 이 정해지지 않아 next/image 를 쓰지 않는다(메인 전시와 같다)
            // eslint-disable-next-line @next/next/no-img-element
            <img
              key={url}
              src={url}
              alt=""
              decoding="async"
              onLoad={(event) => {
                const { naturalWidth, naturalHeight } = event.currentTarget;
                if (naturalWidth > 0 && naturalHeight > 0) {
                  onMeasured({ url, width: naturalWidth, height: naturalHeight });
                }
              }}
              onError={() => setFailedUrl(url)}
              className="aspect-[3/4] w-full rounded bg-muted object-cover"
            />
          ) : (
            <div className="flex aspect-[3/4] w-full items-center justify-center rounded bg-muted p-2 text-center text-xs text-muted-fg">
              {failed ? '이미지를 불러오지 못했습니다' : '이미지 없음'}
            </div>
          )}
        </div>
        <div className="flex min-w-0 flex-1 flex-col gap-4">
          <TextInput
            label={LOOK_LABELS.imageUrl}
            value={form.imageUrl}
            max={LOOK_LIMITS.imageUrl}
            multiline={false}
            hint={
              failed
                ? '이 주소의 이미지를 불러오지 못했습니다. 주소가 맞는지 확인해 주세요.'
                : undefined
            }
            errors={errorsOf('imageUrl')}
            onChange={(imageUrl) => onChange({ imageUrl })}
          />
          <TextInput
            label={LOOK_LABELS.altText}
            value={form.altText}
            max={LOOK_LIMITS.altText}
            multiline={false}
            hint={
              form.altText.trim() === ''
                ? '비어 있으면 화면 낭독기가 이 사진을 건너뜁니다. 사진에 보이는 것을 짧게 적어 주세요.'
                : '사진을 볼 수 없는 고객에게 읽어 주는 문장입니다.'
            }
            errors={errorsOf('altText')}
            onChange={(altText) => onChange({ altText })}
          />
        </div>
      </div>
    </fieldset>
  );
}
