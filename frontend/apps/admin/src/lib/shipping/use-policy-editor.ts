'use client';

import { apiFetch } from '@cf/api-client';
import { useCallback, useEffect, useState } from 'react';
import { accessDeniedOf, type AccessDenied } from '@/lib/api-errors';
import {
  CONFLICT_NOTICE,
  policySaveFailure,
  UNKNOWN_OUTCOME_NOTICE,
  withSummary,
} from './policy-errors';
import type { BuildResult, FormErrors } from './policy-form';

export type EditorStatus =
  | { kind: 'loading' }
  | { kind: 'denied'; denied: AccessDenied }
  | { kind: 'loadFailed' }
  | { kind: 'ready' };

export type PolicyEditor<Form> = {
  status: EditorStatus;
  form: Form | null;
  /**
   * 칸을 고친다. 저장 안내는 지우고, 오류는 다음 저장 때 다시 본다.
   * 줄을 더하거나 지울 때는 `rowsChanged` 로 오류를 지운다 — 오류 키가 줄 순번(`remoteAreas.1.…`)이라 엉뚱한 줄에 붙지 않게.
   */
  update: (next: Form, options?: { rowsChanged?: boolean }) => void;
  errors: FormErrors;
  /** 저장 결과 · 충돌 안내 */
  notice: string | null;
  saving: boolean;
  /**
   * 저장 시도 뒤 서버 값이 바뀌었을 수 있으면 오른다 — 저장 성공 · 충돌로 다시 읽음 · 결과 모름으로 다시 읽음.
   * 변경 이력처럼 같은 정책을 읽는 곁 화면이 `key` 로 써서 다시 읽는다(#319).
   */
  revision: number;
  save: () => Promise<void>;
  reload: () => Promise<void>;
};

/**
 * 배송비 정책 편집 한 화면의 상태 — 읽기 · 폼 · 저장(전체 교체 PUT + version).
 * - GET 이 null 이면 처음 만들기 폼(version null)이다.
 * - 409 DELIVERY_006(다른 곳에서 먼저 저장)이면 최신 값을 다시 읽어 폼을 바꾸고 안내한다 — 덮어쓰지 않는다.
 * - 401 · 403 은 로그인 · 권한 안내 상태로 바꾼다(401 이면 로그인 화면으로 간다, #204).
 */
export function usePolicyEditor<Policy, Form, Request>({
  path,
  toForm,
  toRequest,
}: {
  path: string;
  toForm: (policy: Policy | null) => Form;
  toRequest: (form: Form) => BuildResult<Request>;
}): PolicyEditor<Form> {
  const [status, setStatus] = useState<EditorStatus>({ kind: 'loading' });
  const [form, setForm] = useState<Form | null>(null);
  const [errors, setErrors] = useState<FormErrors>({});
  const [notice, setNotice] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [revision, setRevision] = useState(0);

  // 상태는 응답 콜백에서만 바꾼다 — 마운트 effect 가 부르므로 동기 setState 를 두지 않는다
  const load = useCallback(
    (): Promise<boolean> =>
      apiFetch<Policy | null>(path).then(
        (policy) => {
          setForm(toForm(policy));
          setStatus({ kind: 'ready' });
          return true;
        },
        (cause: unknown) => {
          const denied = accessDeniedOf(cause);
          setStatus(denied ? { kind: 'denied', denied } : { kind: 'loadFailed' });
          return false;
        },
      ),
    [path, toForm],
  );

  useEffect(() => {
    void load();
  }, [load]);

  const save = async () => {
    if (!form || saving) return;
    const built = toRequest(form);
    if (!built.ok) {
      setErrors(withSummary(built.errors));
      setNotice(null);
      return;
    }
    setSaving(true);
    setErrors({});
    setNotice(null);
    try {
      const saved = await apiFetch<Policy>(path, { method: 'PUT', json: built.request });
      setForm(toForm(saved));
      setNotice('저장했습니다.');
      setRevision((n) => n + 1);
    } catch (cause) {
      const failure = policySaveFailure(cause);
      if (failure.kind === 'access') setStatus({ kind: 'denied', denied: failure.denied });
      else if (failure.kind === 'conflict') {
        if (await load()) setNotice(CONFLICT_NOTICE);
        setRevision((n) => n + 1);
      } else if (failure.kind === 'unknownOutcome') {
        if (await load()) setNotice(UNKNOWN_OUTCOME_NOTICE);
        setRevision((n) => n + 1);
      } else setErrors(failure.errors);
    } finally {
      setSaving(false);
    }
  };

  const reload = async () => {
    setStatus({ kind: 'loading' });
    setErrors({});
    setNotice(null);
    await load();
  };

  return {
    status,
    form,
    update: (next, options) => {
      setForm(next);
      setNotice(null);
      if (options?.rowsChanged) setErrors({});
    },
    errors,
    notice,
    saving,
    revision,
    save,
    reload,
  };
}
