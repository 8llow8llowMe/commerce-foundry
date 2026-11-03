'use client';

import { apiFetch, ApiError } from '@cf/api-client';
import { useRouter } from 'next/navigation';
import { useId, useRef, useState } from 'react';
import { accessDeniedOf, LOAD_FAILED, type FormErrors } from '@/lib/api-errors';
import { productDeletedHref } from './delete';
import { adminProductPath, type AdminProductDetail } from './detail';
import { sectionFailure, type SectionFailureOptions } from './section';
import { VERSION_MISSING } from './update';

export type SectionBusy = 'saving' | 'reloading' | null;

export type SectionSave = {
  errors: FormErrors;
  errorsOf: (key: string) => string[];
  /** 충돌 · 결과 모름 — 오류 요약에 "최신 내용 불러오기" 를 낸다 */
  reload: boolean;
  busy: SectionBusy;
  forbidden: boolean;
  /** 오류 요약 영역 id — 오류가 나면 포커스를 옮겨 화면 읽기 프로그램에도 알린다 */
  summaryId: string;
  showErrors: (errors: FormErrors, offerReload?: boolean) => void;
  clearErrors: () => void;
  /**
   * 저장. `build` 는 상세 버전으로 본문을 만들고(칸 오류면 `errors`), `send` 는 보낸 뒤 편집용 상세를 돌려준다.
   * 성공하면 `onSaved`, 실패하면 폼을 지키고 문구를 보인다. 자동으로 다시 보내지 않는다.
   */
  save: <Body>(
    build: (version: number) => { ok: true; request: Body } | { ok: false; errors: FormErrors },
    send: (request: Body) => Promise<AdminProductDetail>,
  ) => Promise<void>;
  /** 상세를 다시 읽어 `onReloaded` 로 넘긴다(입력은 최신 값으로 바뀐다) */
  reloadLatest: () => Promise<void>;
};

/**
 * 상품 섹션 저장의 공통 상태(#29). 상품(`product`)은 부르는 쪽이 들고 있다 — 옵션 · SKU 화면은 여러 폼이 같은 상품을 나눠 쓴다.
 * 401 은 폼을 지키고 다시 로그인하라고 알리고, 403 은 `forbidden`(화면이 `AccessNotice`), 없는 상품은 목록으로 보낸다.
 * 저장이 끝나도(`onSaved`) 바쁨 표시를 풀지 않는 쪽은 부르는 쪽이 고른다 — 상세로 옮기는 화면은 옮길 때까지 버튼을 막아 둔다.
 */
export function useSectionSave({
  product,
  onSaved,
  onReloaded,
  keepBusyAfterSave = false,
  ...failureOptions
}: {
  product: AdminProductDetail;
  onSaved: (saved: AdminProductDetail) => void;
  onReloaded: (latest: AdminProductDetail) => void;
  /** 성공 뒤 다른 화면으로 옮긴다 — 옮길 때까지 버튼을 막아 둔다 */
  keepBusyAfterSave?: boolean;
} & SectionFailureOptions): SectionSave {
  const router = useRouter();
  const [errors, setErrors] = useState<FormErrors>({});
  const [reload, setReload] = useState(false);
  const [busy, setBusy] = useState<SectionBusy>(null);
  const [forbidden, setForbidden] = useState(false);
  const submitting = useRef(false);
  const summaryId = useId();

  const showErrors = (next: FormErrors, offerReload = false) => {
    setErrors(next);
    setReload(offerReload);
    // 요약으로 포커스를 옮겨 화면 읽기 프로그램에도 알린다
    requestAnimationFrame(() => document.getElementById(summaryId)?.focus());
  };
  const clearErrors = () => {
    setErrors({});
    setReload(false);
  };

  const save: SectionSave['save'] = async (build, send) => {
    if (submitting.current) return;
    if (product.version === null) {
      showErrors({ form: [VERSION_MISSING] });
      return;
    }
    const built = build(product.version);
    if (!built.ok) {
      showErrors(built.errors);
      return;
    }
    submitting.current = true;
    setBusy('saving');
    clearErrors();
    try {
      const saved = await send(built.request);
      if (!keepBusyAfterSave) {
        submitting.current = false;
        setBusy(null);
      }
      onSaved(saved);
    } catch (cause) {
      submitting.current = false;
      setBusy(null);
      const failure = sectionFailure(cause, failureOptions);
      if (failure.kind === 'forbidden') setForbidden(true);
      else if (failure.kind === 'gone') router.replace(productDeletedHref('gone'));
      else showErrors(failure.errors, failure.reload);
    }
  };

  const reloadLatest = async () => {
    setBusy('reloading');
    try {
      const latest = await apiFetch<AdminProductDetail>(adminProductPath(product.productId));
      clearErrors();
      onReloaded(latest);
    } catch (cause) {
      const denied = accessDeniedOf(cause);
      if (denied === 'forbidden') setForbidden(true);
      else if (cause instanceof ApiError && cause.kind === 'notFound') {
        router.replace(productDeletedHref('gone'));
      } else showErrors({ form: [LOAD_FAILED] }, true);
    } finally {
      setBusy(null);
    }
  };

  return {
    errors,
    errorsOf: (key) => errors[key] ?? [],
    reload,
    busy,
    forbidden,
    summaryId,
    showErrors,
    clearErrors,
    save,
    reloadLatest,
  };
}
