'use client';

import { ApiError, apiFetch } from '@cf/api-client';
import { Button, FieldError, TextField } from '@cf/ui';
import { useRouter } from 'next/navigation';
import { useState, type FormEvent } from 'react';
import { useDictionary, useLocalizedHref } from '@/components/i18n/LocaleProvider';
import { Link } from '@/components/i18n/Link';
import type { Dictionary } from '@/lib/i18n/dictionaries';

export type LoginErrors = { form: string[]; email: string[]; password: string[] };

const NO_ERRORS: LoginErrors = { form: [], email: [], password: [] };

/**
 * 이메일 로그인 폼(#25). BFF `POST /api/v1/auth/login` 이 토큰을 httpOnly 쿠키로 심고 토큰 없는 성공만 돌려준다.
 * 성공하면 돌아갈 경로로 바꾸고 서버 컴포넌트를 다시 그린다(로그인 상태 반영). 이동하는 동안 버튼은 막힌 채다.
 * 실패 이유를 이메일 · 비밀번호 중 하나로 짚지 않는다(BE 와 같은 한 문구, 계정 존재 여부를 흘리지 않게).
 */
export function LoginForm({ returnPath }: { returnPath: string }) {
  const t = useDictionary().auth;
  const localize = useLocalizedHref();
  const router = useRouter();
  const [submitting, setSubmitting] = useState(false);
  const [errors, setErrors] = useState<LoginErrors>(NO_ERRORS);

  const signUpHref =
    returnPath === '/' ? '/signup' : `/signup?next=${encodeURIComponent(returnPath)}`;

  const onSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (submitting) return;
    const data = new FormData(event.currentTarget);
    setSubmitting(true);
    setErrors(NO_ERRORS);
    try {
      await apiFetch<null>('/api/v1/auth/login', {
        method: 'POST',
        json: {
          email: String(data.get('email') ?? ''),
          password: String(data.get('password') ?? ''),
        },
      });
      router.replace(localize(returnPath));
      router.refresh();
    } catch (cause) {
      setSubmitting(false);
      setErrors(loginErrors(cause, t));
    }
  };

  return (
    <div className="flex flex-col gap-8">
      <form onSubmit={onSubmit} className="flex flex-col gap-5">
        <TextField
          label={t.login.email}
          name="email"
          type="email"
          autoComplete="email"
          inputMode="email"
          maxLength={254}
          required
          errors={errors.email}
        />
        <TextField
          label={t.login.password}
          name="password"
          type="password"
          autoComplete="current-password"
          required
          errors={errors.password}
        />
        {/* 폼 전체 오류는 버튼 바로 위 — 누른 자리 가까이에서 읽힌다 */}
        <FieldError messages={errors.form} />
        <Button type="submit" disabled={submitting} className="h-12 w-full tracking-[0.15em]">
          {submitting ? t.login.submitting : t.login.submit}
        </Button>
      </form>

      <p className="flex justify-center gap-2 text-sm text-muted-fg">
        {t.login.noAccount}
        <Link href={signUpHref} className="text-fg underline underline-offset-4">
          {t.login.signUp}
        </Link>
      </p>
    </div>
  );
}

/**
 * 로그인 실패를 화면 문구로. 서버 코드로 사전 문구를 고르고(영문 스토어에서도 같은 뜻), 사전에 없으면 서버 문구.
 * 로그인 실패는 401 이 아니라 400 AUTH_001(fieldErrors 없음) — kind 가 아니라 code 로 본다.
 * 그 밖의 실패(연결 · 일시 장애 · 예상 밖)는 한 문구로 다시 시도를 권한다.
 */
export function loginErrors(cause: unknown, t: Dictionary['auth']): LoginErrors {
  if (!(cause instanceof ApiError)) return { ...NO_ERRORS, form: [t.failed] };
  const textOf = (code: string | null, fallback: string) => (code && t.errors[code]) || fallback;

  if (cause.fieldErrors.length > 0) {
    const pick = (field: string) =>
      cause.fieldErrors
        .filter((item) => item.field === field)
        .map((item) => textOf(item.code, item.message));
    const other = cause.fieldErrors.some(
      (item) => item.field !== 'email' && item.field !== 'password',
    );
    return {
      email: pick('email'),
      password: pick('password'),
      form: other ? [t.failed] : [],
    };
  }
  if (cause.code === 'AUTH_001') return { ...NO_ERRORS, form: [textOf(cause.code, cause.message)] };
  return { ...NO_ERRORS, form: [t.failed] };
}
