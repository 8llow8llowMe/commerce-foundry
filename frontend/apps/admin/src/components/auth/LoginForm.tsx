'use client';

import { apiFetch } from '@cf/api-client';
import { Button, FieldError, TextField } from '@cf/ui';
import { useRouter } from 'next/navigation';
import { useState, type FormEvent } from 'react';
import { loginErrors, NO_LOGIN_ERRORS, type LoginErrors } from '@/lib/auth/login-errors';

/**
 * 관리자 로그인 폼(#204). BFF `POST /api/v1/auth/admin/login` 이 access 토큰을 httpOnly 쿠키로 심고 토큰 없는 성공만 돌려준다.
 * 성공하면 돌아갈 경로로 바꾸고 서버 컴포넌트를 다시 그린다. 이동하는 동안 버튼은 막힌 채다.
 */
export function LoginForm({ returnPath }: { returnPath: string }) {
  const router = useRouter();
  const [submitting, setSubmitting] = useState(false);
  const [errors, setErrors] = useState<LoginErrors>(NO_LOGIN_ERRORS);

  const onSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (submitting) return;
    const data = new FormData(event.currentTarget);
    setSubmitting(true);
    setErrors(NO_LOGIN_ERRORS);
    try {
      await apiFetch<null>('/api/v1/auth/admin/login', {
        method: 'POST',
        json: {
          email: String(data.get('email') ?? ''),
          password: String(data.get('password') ?? ''),
        },
      });
      router.replace(returnPath);
      router.refresh();
    } catch (cause) {
      setSubmitting(false);
      setErrors(loginErrors(cause));
    }
  };

  return (
    <form onSubmit={onSubmit} className="flex flex-col gap-5">
      <TextField
        label="이메일"
        name="email"
        type="email"
        autoComplete="username"
        inputMode="email"
        maxLength={254}
        required
        errors={errors.email}
      />
      <TextField
        label="비밀번호"
        name="password"
        type="password"
        autoComplete="current-password"
        required
        errors={errors.password}
      />
      {/* 폼 전체 오류는 버튼 바로 위 — 누른 자리 가까이에서 읽힌다 */}
      <FieldError messages={errors.form} />
      <Button type="submit" disabled={submitting} className="h-11 w-full">
        {submitting ? '로그인하는 중…' : '로그인'}
      </Button>
    </form>
  );
}
