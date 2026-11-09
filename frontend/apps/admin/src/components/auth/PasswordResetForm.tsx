'use client';

import { apiFetch } from '@cf/api-client';
import { Button, FieldError, TextField } from '@cf/ui';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import {
  PASSWORD_CONFIRM_MISMATCH,
  PASSWORD_HINT,
  PASSWORD_MAX,
  passwordProblem,
} from '@/lib/admin-account/errors';
import {
  PASSWORD_RESET_API,
  RESET_LINK_SPENT,
  resetFailure,
  tokenFromHash,
} from '@/lib/admin-account/password-reset';
import type { FormErrors } from '@/lib/api-errors';
import { PASSWORD_RESET_LOGIN } from '@/lib/auth/login-notice';

const CHECK_INPUT = '입력값을 확인해 주세요.';

type Phase = { kind: 'reading' } | { kind: 'ready' } | { kind: 'spent'; message: string };

/**
 * 메일 링크로 운영자 비밀번호를 새로 정한다(#237, BE #589). 로그인 밖 화면이다.
 * 토큰은 주소의 `#token=` 에서 한 번 읽어 ref 에만 두고 바로 주소에서 지운다 — 새로 고침 · 뒤로 가기 · 화면 공유에 남지 않게.
 * 비밀번호 칸은 제어하지 않는다(비밀번호를 React 상태 · 주소 · 로그에 두지 않는다).
 * 성공하면 서버가 그 계정의 기존 로그인을 끊는다. 이 브라우저의 다른 계정 로그인은 건드리지 않고 로그인 화면으로 보낸다.
 */
export function PasswordResetForm() {
  const router = useRouter();
  const token = useRef<string | null>(null);
  const [phase, setPhase] = useState<Phase>({ kind: 'reading' });
  const [submitting, setSubmitting] = useState(false);
  const [errors, setErrors] = useState<FormErrors>({});

  useEffect(() => {
    const { hash, pathname, search } = window.location;
    // 한 번만 읽는다 — effect 가 다시 돌면(개발 모드 StrictMode 등) 이미 지운 주소를 읽어 토큰을 잃는다. ref 는 그대로 남는다
    if (hash) {
      token.current ??= tokenFromHash(hash);
      window.history.replaceState(window.history.state, '', pathname + search);
    }
    // 주소(외부 값)를 읽은 결과를 그린다 — 서버 렌더에는 fragment 가 없어 마운트 뒤에만 알 수 있다
    setPhase(token.current ? { kind: 'ready' } : { kind: 'spent', message: RESET_LINK_SPENT });
  }, []);

  const onSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (submitting || !token.current) return;
    const data = new FormData(event.currentTarget);
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
      await apiFetch<null>(PASSWORD_RESET_API, {
        method: 'POST',
        json: { token: token.current, newPassword },
      });
    } catch (cause) {
      setSubmitting(false);
      const failure = resetFailure(cause);
      if (failure.kind === 'spent') {
        token.current = null;
        setPhase(failure);
      } else {
        setErrors(failure.errors);
      }
      return;
    }
    token.current = null;
    // 이동하는 동안 버튼은 막힌 채다
    router.replace(PASSWORD_RESET_LOGIN);
  };

  if (phase.kind === 'reading') {
    return <p className="text-center text-sm text-muted-fg">링크를 확인하는 중…</p>;
  }

  if (phase.kind === 'spent') {
    return (
      <div className="flex flex-col gap-4">
        <p role="alert" className="rounded-md bg-muted px-4 py-3 text-sm">
          {phase.message}
        </p>
        <Link href="/login" className="text-sm underline underline-offset-4">
          로그인 화면으로
        </Link>
      </div>
    );
  }

  const fieldOnly = !errors.form?.length && Object.values(errors).some((list) => list.length > 0);

  return (
    <form onSubmit={onSubmit} aria-label="비밀번호 재설정" className="flex flex-col gap-5">
      <p className="text-sm text-muted-fg">
        새 비밀번호를 정하면 이 계정의 기존 로그인은 모두 끊기고, 새 비밀번호로 다시 로그인합니다.
      </p>
      <fieldset disabled={submitting} className="flex flex-col gap-5">
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
      <Button type="submit" disabled={submitting}>
        {submitting ? '정하는 중…' : '새 비밀번호 정하기'}
      </Button>
    </form>
  );
}
