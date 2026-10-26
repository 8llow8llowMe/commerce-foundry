'use client';

import { apiFetch } from '@cf/api-client';
import { Button, FieldError, TextField } from '@cf/ui';
import { useRouter } from 'next/navigation';
import { useState, type FormEvent } from 'react';
import { AccessNotice } from '@/components/AccessNotice';
import {
  changePasswordFailure,
  PASSWORD_CONFIRM_MISMATCH,
  PASSWORD_HINT,
  PASSWORD_MAX,
  passwordProblem,
} from '@/lib/admin-account/errors';
import { MY_PASSWORD_PATH } from '@/lib/admin-account/query';
import type { AccessDenied, FormErrors } from '@/lib/api-errors';
import { PASSWORD_CHANGED_LOGIN } from '@/lib/auth/login-notice';

const CHECK_INPUT = '입력값을 확인해 주세요.';

/**
 * 내 비밀번호 변경(#352, BE #193 `PATCH /api/v1/admin/me/password`). 칸은 제어하지 않는다 — 비밀번호를 React 상태 · 주소 · 로그에 두지 않는다.
 * 성공하면 서버가 지금 access 토큰을 끊으므로 로그아웃(쿠키 삭제)하고 로그인 화면으로 보낸다.
 * 로그아웃 요청이 실패해도 토큰은 이미 막혔으니 로그인 화면으로 간다(새로 로그인하면 쿠키를 덮는다).
 */
export function PasswordChangeForm() {
  const router = useRouter();
  const [submitting, setSubmitting] = useState(false);
  const [errors, setErrors] = useState<FormErrors>({});
  const [denied, setDenied] = useState<AccessDenied | null>(null);

  const onSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (submitting) return;
    const data = new FormData(event.currentTarget);
    const currentPassword = String(data.get('currentPassword') ?? '');
    const newPassword = String(data.get('newPassword') ?? '');
    const problem = passwordProblem(newPassword);
    if (problem) {
      setErrors({ newPassword: [problem] });
      return;
    }
    if (newPassword !== String(data.get('newPasswordConfirm') ?? '')) {
      setErrors({ newPasswordConfirm: [PASSWORD_CONFIRM_MISMATCH] });
      return;
    }
    setSubmitting(true);
    setErrors({});
    try {
      await apiFetch<null>(MY_PASSWORD_PATH, {
        method: 'PATCH',
        json: { currentPassword, newPassword },
      });
    } catch (cause) {
      setSubmitting(false);
      const failure = changePasswordFailure(cause);
      if (failure.kind === 'access') setDenied(failure.denied);
      else if (failure.kind === 'errors') setErrors(failure.errors);
      // `changed`(503 AUTH_013)는 바뀐 것이다 — 아래 성공 경로로
      if (failure.kind !== 'changed') return;
      setSubmitting(true);
    }
    // 이동하는 동안 버튼은 막힌 채다
    await apiFetch<null>('/api/v1/auth/logout', { method: 'POST' }).catch(() => undefined);
    router.replace(PASSWORD_CHANGED_LOGIN);
    router.refresh();
  };

  if (denied) return <AccessNotice denied={denied} />;

  const fieldOnly = !errors.form?.length && Object.values(errors).some((list) => list.length > 0);

  return (
    <form onSubmit={onSubmit} aria-label="비밀번호 변경" className="flex max-w-md flex-col gap-5">
      <fieldset disabled={submitting} className="flex flex-col gap-5">
        <TextField
          label="현재 비밀번호"
          name="currentPassword"
          type="password"
          autoComplete="current-password"
          required
          errors={errors.currentPassword}
        />
        <div className="flex flex-col gap-1">
          <TextField
            label="새 비밀번호"
            name="newPassword"
            type="password"
            autoComplete="new-password"
            maxLength={PASSWORD_MAX}
            required
            errors={errors.newPassword}
          />
          <p className="text-xs text-muted-fg">{PASSWORD_HINT}</p>
        </div>
        <TextField
          label="새 비밀번호 확인"
          name="newPasswordConfirm"
          type="password"
          autoComplete="new-password"
          maxLength={PASSWORD_MAX}
          required
          errors={errors.newPasswordConfirm}
        />
      </fieldset>
      <FieldError messages={errors.form ?? (fieldOnly ? [CHECK_INPUT] : [])} />
      <Button type="submit" disabled={submitting} className="self-start">
        {submitting ? '바꾸는 중…' : '비밀번호 바꾸기'}
      </Button>
    </form>
  );
}
