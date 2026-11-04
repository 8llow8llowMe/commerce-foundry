'use client';

import { ApiError, apiFetch } from '@cf/api-client';
import { useCallback, useEffect, useState } from 'react';
import { accessDeniedOf, type AccessDenied, type FormErrors } from '@/lib/api-errors';
import {
  CHECK_FIELDS,
  CONFLICT_NOTICE,
  RELOAD_FAILED,
  SAVED_AFTER_UNKNOWN,
  storeProfileSaveFailure,
  UNKNOWN_OUTCOME_NOTICE,
} from './errors';
import {
  diffFields,
  storeProfileForm,
  storeProfileRequest,
  type FieldDiff,
  type StoreProfileForm,
} from './form';
import { NOT_CONFIGURED, STORE_PROFILE_PATH, type StoreProfile } from './types';

export type EditorStatus =
  | { kind: 'loading' }
  | { kind: 'denied'; denied: AccessDenied }
  | { kind: 'loadFailed' }
  | { kind: 'ready' };

/** 충돌 · 결과 모름 뒤 최신 값과의 비교. latest 가 null 이면 서버에 아직 등록이 없다 */
export type Comparison = { latest: StoreProfile | null; diffs: FieldDiff[] };

/** 미등록 404 는 빈 폼(null), 그 밖의 실패는 던진다 */
async function fetchProfile(): Promise<StoreProfile | null> {
  try {
    return await apiFetch<StoreProfile>(STORE_PROFILE_PATH);
  } catch (cause) {
    if (cause instanceof ApiError && cause.code === NOT_CONFIGURED) return null;
    throw cause;
  }
}

/**
 * 쇼핑몰 정보 편집 화면의 상태 — 읽기 · 폼 · 저장(전체 교체 PUT + expectedVersion).
 * - 미등록(404 STORE_PROFILE_001)이면 빈 폼으로 처음 만든다(expectedVersion null).
 * - 409 STORE_PROFILE_020 · 결과 모름(502 · 504)이면 **입력을 지운 채 덮어쓰지 않는다** — 최신 값을 다시 읽어
 *   다른 칸을 나란히 보이고, 다음 저장은 최신 version 으로 보낸다(확인한 뒤 내 값으로 저장) 또는 최신 값으로 바꾼다.
 * - 401 · 403 읽기는 로그인 · 권한 안내 상태로, 저장 중 401 은 폼을 지킨 채 다시 로그인을 안내한다.
 */
export function useStoreProfileEditor() {
  const [status, setStatus] = useState<EditorStatus>({ kind: 'loading' });
  const [form, setForm] = useState<StoreProfileForm | null>(null);
  const [errors, setErrors] = useState<FormErrors>({});
  const [notice, setNotice] = useState<string | null>(null);
  const [comparison, setComparison] = useState<Comparison | null>(null);
  const [saving, setSaving] = useState(false);

  // 상태는 응답 콜백에서만 바꾼다 — 마운트 effect 가 부르므로 동기 setState 를 두지 않는다
  const load = useCallback(
    () =>
      fetchProfile().then(
        (profile) => {
          setForm(storeProfileForm(profile));
          setStatus({ kind: 'ready' });
        },
        (cause: unknown) => {
          const denied = accessDeniedOf(cause);
          setStatus(denied ? { kind: 'denied', denied } : { kind: 'loadFailed' });
        },
      ),
    [],
  );

  useEffect(() => {
    void load();
  }, [load]);

  /** 저장이 막힌 뒤 최신 값을 읽어 내 입력과 비교한다. 입력은 그대로, version 만 최신으로 */
  const compareWithLatest = async (
    mine: StoreProfileForm,
    reason: 'conflict' | 'unknownOutcome',
  ) => {
    let latest: StoreProfile | null;
    try {
      latest = await fetchProfile();
    } catch (cause) {
      const denied = accessDeniedOf(cause);
      if (denied === 'forbidden') setStatus({ kind: 'denied', denied });
      else setErrors({ form: [RELOAD_FAILED] });
      return;
    }
    const diffs = diffFields(mine, latest);
    setForm({ ...mine, version: latest?.version ?? null });
    if (reason === 'unknownOutcome' && diffs.length === 0) {
      setNotice(SAVED_AFTER_UNKNOWN);
      return;
    }
    setComparison({ latest, diffs });
    setNotice(reason === 'conflict' ? CONFLICT_NOTICE : UNKNOWN_OUTCOME_NOTICE);
  };

  const save = async () => {
    if (!form || saving) return;
    const built = storeProfileRequest(form);
    if (!built.ok) {
      setErrors({ ...built.errors, form: [CHECK_FIELDS] });
      setNotice(null);
      return;
    }
    setSaving(true);
    setErrors({});
    setNotice(null);
    setComparison(null);
    try {
      const saved = await apiFetch<StoreProfile>(STORE_PROFILE_PATH, {
        method: 'PUT',
        json: built.request,
      });
      setForm(storeProfileForm(saved));
      setNotice('저장했습니다. 스토어프론트에 바로 공개됩니다.');
    } catch (cause) {
      const failure = storeProfileSaveFailure(cause);
      if (failure.kind === 'access') setStatus({ kind: 'denied', denied: failure.denied });
      else if (failure.kind === 'errors') setErrors(failure.errors);
      else await compareWithLatest(form, failure.kind);
    } finally {
      setSaving(false);
    }
  };

  return {
    status,
    form,
    errors,
    notice,
    comparison,
    saving,
    save,
    update: (next: StoreProfileForm) => {
      setForm(next);
      setNotice(null);
    },
    /** 비교 중 내 입력을 버리고 최신 값으로 */
    applyLatest: () => {
      if (!comparison) return;
      setForm(storeProfileForm(comparison.latest));
      setComparison(null);
      setErrors({});
      setNotice('최신 값으로 바꿨습니다.');
    },
    reload: async () => {
      setStatus({ kind: 'loading' });
      setErrors({});
      setNotice(null);
      setComparison(null);
      await load();
    },
  };
}

export type StoreProfileEditor = ReturnType<typeof useStoreProfileEditor>;
