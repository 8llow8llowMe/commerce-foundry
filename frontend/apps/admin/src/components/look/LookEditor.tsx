'use client';

import { apiFetch } from '@cf/api-client';
import { Button, Checkbox, FieldError } from '@cf/ui';
import { useEffect, useId, useRef, useState } from 'react';
import { TextInput } from '@/components/display/SectionEditor';
import { ProductPickField } from '@/components/display/ProductPickField';
import { DisplayImageUploadButton } from '@/components/upload/DisplayImageUploadButton';
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
  // 올리는 중 — 확정한 주소가 들어오기 전에 저장하면 이미지가 빠지므로 그동안 저장을 막는다(메인 전시와 같다)
  const [uploads, setUploads] = useState(0);
  const uploading = uploads > 0;
  const errorsOf = (key: string) => errors[key] ?? [];
  const set = (patch: Partial<LookForm>) => setForm((current) => ({ ...current, ...patch }));

  const submit = async () => {
    if (saving || uploading) return;
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
      else {
        setErrors(failure.errors);
        // 삭제 상품(`LOOK_009`)은 그 줄에 삭제 표시를 한다 — 아이디는 화면에 내지 않는다(#305)
        const deleted = new Set(failure.deletedProductIds ?? []);
        if (deleted.size > 0) {
          setForm((current) => ({
            ...current,
            products: current.products.map((item) =>
              deleted.has(item.productId) ? { ...item, deleted: true } : item,
            ),
          }));
        }
      }
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
          onUploadingChange={(busy) => setUploads((count) => Math.max(0, count + (busy ? 1 : -1)))}
          onAccessDenied={onDenied}
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
      {uploading && (
        <p id={`${id}-uploading`} className="text-sm text-muted-fg">
          이미지를 올리는 중입니다. 다 올라가면 저장할 수 있습니다.
        </p>
      )}
      <div className="flex gap-2">
        <Button
          type="submit"
          disabled={saving || uploading}
          aria-describedby={uploading ? `${id}-uploading` : undefined}
        >
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
 * 대표 이미지 — 파일을 올리면(#706, BE #508 전시 · 룩 공용 발급 → PUT → 확정) 확정한 공개 주소가 주소 칸에 들어간다.
 * 메인 전시 미디어 칸과 같은 `DisplayImageUploadButton` 이다. 주소 칸은 그대로 둬서 이미 주소로 저장한 룩도 보이고 고칠 수 있다.
 * 저장소 주소인지는 서버가 본다(`LOOK_005`). 주소의 이미지를 작게 보여 주고, 불러오면 원본 크기를 함께 보낸다
 * (서버는 크기를 계산하지 않는다 — 고객 화면이 자리를 미리 잡는 데 쓴다).
 */
export function ImageFields({
  form,
  errorsOf,
  onChange,
  onMeasured,
  onUploadingChange,
  onAccessDenied,
}: {
  form: Pick<LookForm, 'imageUrl' | 'altText'>;
  errorsOf: (key: string) => string[];
  onChange: (patch: Partial<Pick<LookForm, 'imageUrl' | 'altText'>>) => void;
  onMeasured: (size: { url: string; width: number; height: number }) => void;
  onUploadingChange?: (uploading: boolean) => void;
  onAccessDenied?: (denied: AccessDenied) => void;
}) {
  const url = normalizeText(form.imageUrl);
  const [failedUrl, setFailedUrl] = useState<string | null>(null);
  const failed = url !== null && failedUrl === url;
  return (
    <fieldset className="flex min-w-0 flex-col gap-3">
      <legend className="text-sm font-medium">대표 이미지</legend>
      <p className="text-xs text-muted-fg">
        파일을 올리면 주소 칸이 채워집니다. 쇼핑몰 이미지 저장소에 이미 있는 이미지는 주소를 그대로
        넣어도 됩니다. 다른 사이트의 이미지 주소는 저장되지 않습니다.
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
          <DisplayImageUploadButton
            label="대표 이미지 파일 올리기"
            onUploaded={(imageUrl) => onChange({ imageUrl })}
            onUploadingChange={onUploadingChange}
            onAccessDenied={onAccessDenied}
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
