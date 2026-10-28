'use client';

import { ApiError, apiFetch } from '@cf/api-client';
import { Button, FieldError, TextField } from '@cf/ui';
import { useRouter } from 'next/navigation';
import { useState, type FormEvent } from 'react';
import { useDictionary, useLocalizedHref } from '@/components/i18n/LocaleProvider';
import { Link } from '@/components/i18n/Link';
import { SocialLoginButtons } from './SocialLoginButtons';
import { rateLimitTextOf } from '@/lib/auth/rate-limit';
import type { Dictionary } from '@/lib/i18n/dictionaries';
import { useRetryCooldown } from '@/lib/use-retry-cooldown';

export type LoginErrors = { form: string[]; email: string[]; password: string[] };

const NO_ERRORS: LoginErrors = { form: [], email: [], password: [] };

/**
 * 이메일 로그인 폼(#25). BFF `POST /api/v1/auth/login` 이 토큰을 httpOnly 쿠키로 심고 토큰 없는 성공만 돌려준다.
 * 성공하면 돌아갈 경로로 바꾸고 서버 컴포넌트를 다시 그린다(로그인 상태 반영). 이동하는 동안 버튼은 막힌 채다.
 * 실패 이유를 이메일 · 비밀번호 중 하나로 짚지 않는다(BE 와 같은 한 문구, 계정 존재 여부를 흘리지 않게).
 * 시도 제한(429 AUTH_040)이면 안내와 함께 버튼을 `Retry-After` 동안 막는다. `rateLimited` 는 가입 직후 자동 로그인이
 * 막혀 이 화면으로 왔을 때 그 쿨다운을 이어받는 값이다(안내 문구는 페이지가 보인다).
 */
export function LoginForm({
  returnPath,
  rateLimited,
}: {
  returnPath: string;
  rateLimited?: { retryAfterSec: number | null };
}) {
  const t = useDictionary().auth;
  const localize = useLocalizedHref();
  const router = useRouter();
  const [submitting, setSubmitting] = useState(false);
  const [errors, setErrors] = useState<LoginErrors>(NO_ERRORS);
  const cooldown = useRetryCooldown(rateLimited);

  const signUpHref =
    returnPath === '/' ? '/signup' : `/signup?next=${encodeURIComponent(returnPath)}`;

  const onSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (submitting || cooldown.coolingDown) return;
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
      cooldown.startFrom(cause);
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
        <Button
          type="submit"
          disabled={submitting || cooldown.coolingDown}
          className="h-12 w-full tracking-[0.15em]"
        >
          {submitting ? t.login.submitting : t.login.submit}
        </Button>
      </form>

      {/* 간편 로그인은 동의 없이 시작한다 — 신규 가입이면 콜백 화면이 동의를 받아 다시 시작한다 */}
      <SocialLoginButtons returnPath={returnPath} heading={t.social.heading} />

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
 * 시도 제한(429 AUTH_040 · 게이트웨이 GATEWAY_006)은 범용 실패가 아니라 남은 시간을 담은 안내다.
 * 그 밖의 실패(연결 · 일시 장애 · 예상 밖)는 한 문구로 다시 시도를 권한다.
 */
export function loginErrors(cause: unknown, t: Dictionary['auth']): LoginErrors {
  if (!(cause instanceof ApiError)) return { ...NO_ERRORS, form: [t.failed] };
  const limited = rateLimitTextOf(cause, t);
  if (limited) return { ...NO_ERRORS, form: [limited] };
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
