'use client';

import { ApiError, apiFetch } from '@cf/api-client';
import { Button, FieldError, TextField } from '@cf/ui';
import { useRouter } from 'next/navigation';
import { useState, type FormEvent } from 'react';
import { useDictionary, useLocalizedHref } from '@/components/i18n/LocaleProvider';
import { Link } from '@/components/i18n/Link';
import { NO_AGREEMENTS, type SignUpAgreements } from '@/lib/auth/agreements';
import { rateLimitTextOf } from '@/lib/auth/rate-limit';
import type { Dictionary } from '@/lib/i18n/dictionaries';
import { useRetryCooldown } from '@/lib/use-retry-cooldown';
import { AgreementsFieldset } from './AgreementsFieldset';
import { SocialLoginButtons } from './SocialLoginButtons';

type Field =
  | 'email'
  | 'password'
  | 'passwordConfirm'
  | 'name'
  | 'phone'
  | 'agreements'
  | 'termsOfService'
  | 'privacyPolicy'
  | 'marketing';
export type SignUpErrors = Record<Field | 'form', string[]>;

const NO_ERRORS: SignUpErrors = {
  form: [],
  email: [],
  password: [],
  passwordConfirm: [],
  name: [],
  phone: [],
  agreements: [],
  termsOfService: [],
  privacyPolicy: [],
  marketing: [],
};

// BE fieldErrors 의 field 는 요청 본문 경로다 — 동의 칸은 `agreements.termsOfService` 처럼 중첩된다
const SERVER_FIELDS: Record<string, Field> = {
  email: 'email',
  password: 'password',
  name: 'name',
  phone: 'phone',
  agreements: 'agreements',
  'agreements.termsOfService': 'termsOfService',
  'agreements.privacyPolicy': 'privacyPolicy',
  'agreements.marketing': 'marketing',
};

/**
 * 이메일 회원가입 폼(#25). BFF `POST /api/v1/auth/sign-up` 이 가입 뒤 같은 이메일 · 비밀번호로 로그인까지 해 쿠키를 심는다.
 * `signedIn` 이면 돌아갈 경로로, 자동 로그인을 못 했으면(계정은 만들어짐) 가입 완료 안내와 함께 로그인 화면으로 보낸다.
 * 자동 로그인이 요청 제한(429)에 걸렸으면 로그인 화면에 `limited`(Retry-After 초) · `reason`(코드)을 실어 같은 안내 · 쿨다운을 잇는다.
 * 가입 시도 제한(429 AUTH_041)은 안내와 함께 버튼을 `Retry-After` 동안 막는다.
 * 형식 검증은 BE 가 정본이다 — 화면은 필수 · 길이만 브라우저에 맡기고, 비밀번호 확인 일치만 직접 본다(BE 에 없는 칸).
 */
export function SignUpForm({ returnPath }: { returnPath: string }) {
  const t = useDictionary().auth;
  const localize = useLocalizedHref();
  const router = useRouter();
  const [submitting, setSubmitting] = useState(false);
  const [errors, setErrors] = useState<SignUpErrors>(NO_ERRORS);
  const cooldown = useRetryCooldown();
  const [agreements, setAgreements] = useState<SignUpAgreements>(NO_AGREEMENTS);

  const onSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (submitting || cooldown.coolingDown) return;
    const data = new FormData(event.currentTarget);
    const password = String(data.get('password') ?? '');
    if (password !== String(data.get('passwordConfirm') ?? '')) {
      setErrors({ ...NO_ERRORS, passwordConfirm: [t.signUp.passwordMismatch] });
      return;
    }
    const phone = String(data.get('phone') ?? '').trim();

    setSubmitting(true);
    setErrors(NO_ERRORS);
    try {
      const result = await apiFetch<SignUpResult>('/api/v1/auth/sign-up', {
        method: 'POST',
        json: {
          email: String(data.get('email') ?? ''),
          password,
          name: String(data.get('name') ?? ''),
          phone: phone === '' ? null : phone,
          // 세 칸 모두 보낸다 — 마케팅도 빠지면 AUTH_113
          agreements,
        },
      });
      if (result.signedIn) {
        router.replace(localize(returnPath));
        router.refresh();
      } else {
        router.replace(localize(signedUpLoginPath(result, returnPath)));
      }
    } catch (cause) {
      setSubmitting(false);
      cooldown.startFrom(cause);
      setErrors(signUpErrors(cause, t));
    }
  };

  const loginHref = returnPath === '/' ? '/login' : `/login?next=${encodeURIComponent(returnPath)}`;

  return (
    <div className="flex flex-col gap-8">
      <form onSubmit={onSubmit} className="flex flex-col gap-5">
        <TextField
          label={t.signUp.email}
          name="email"
          type="email"
          autoComplete="email"
          inputMode="email"
          maxLength={254}
          required
          errors={errors.email}
        />
        <div className="flex flex-col gap-1">
          <TextField
            label={t.signUp.password}
            name="password"
            type="password"
            autoComplete="new-password"
            minLength={8}
            maxLength={64}
            required
            errors={errors.password}
          />
          {errors.password.length === 0 && (
            <p className="text-sm text-muted-fg">{t.signUp.passwordHint}</p>
          )}
        </div>
        <TextField
          label={t.signUp.passwordConfirm}
          name="passwordConfirm"
          type="password"
          autoComplete="new-password"
          maxLength={64}
          required
          errors={errors.passwordConfirm}
        />
        <TextField
          label={t.signUp.name}
          name="name"
          autoComplete="name"
          maxLength={50}
          required
          errors={errors.name}
        />
        <TextField
          label={t.signUp.phone}
          name="phone"
          type="tel"
          autoComplete="tel"
          inputMode="tel"
          maxLength={13}
          errors={errors.phone}
        />

        <AgreementsFieldset value={agreements} onChange={setAgreements} errors={errors} />

        {/* 폼 전체 오류는 버튼 바로 위 — 누른 자리 가까이에서 읽힌다 */}
        <FieldError messages={errors.form} />
        <Button
          type="submit"
          disabled={submitting || cooldown.coolingDown}
          className="h-12 w-full tracking-[0.15em]"
        >
          {submitting ? t.signUp.submitting : t.signUp.submit}
        </Button>
      </form>

      {/* 간편 가입은 위 약관 동의를 먼저 받고 그 동의를 담아 시작한다(신규 가입이면 BE 가 쓴다) */}
      <SocialLoginButtons
        returnPath={returnPath}
        heading={t.social.signUpHeading}
        agreements={agreements}
        onAgreementsMissing={() =>
          setErrors({ ...NO_ERRORS, agreements: [t.social.agreementsRequired] })
        }
      />

      <p className="flex justify-center gap-2 text-sm text-muted-fg">
        {t.signUp.hasAccount}
        <Link href={loginHref} className="text-fg underline underline-offset-4">
          {t.signUp.login}
        </Link>
      </p>
    </div>
  );
}

/**
 * 가입 실패를 화면 문구로. 서버 코드로 사전 문구를 고르고(영문 스토어에서도 같은 뜻), 사전에 없으면 서버 문구.
 * 검증 오류는 칸마다(중첩 경로는 동의 칸으로), 이메일 중복(409 AUTH_002)은 이메일 칸에 붙인다 — 가입 API 는 중복 확인 API 가 따로 없다.
 * 시도 제한(429 AUTH_041 · 게이트웨이 GATEWAY_006)은 남은 시간을 담은 안내다.
 * 그 밖의 실패(연결 · 일시 장애 · 예상 밖)는 한 문구로 다시 시도를 권한다.
 */
export function signUpErrors(cause: unknown, t: Dictionary['auth']): SignUpErrors {
  if (!(cause instanceof ApiError)) return { ...NO_ERRORS, form: [t.signUp.failed] };
  const limited = rateLimitTextOf(cause, t);
  if (limited) return { ...NO_ERRORS, form: [limited] };
  const textOf = (code: string | null, fallback: string) => (code && t.errors[code]) || fallback;

  if (cause.fieldErrors.length > 0) {
    const errors: SignUpErrors = { ...NO_ERRORS };
    let other = false;
    for (const item of cause.fieldErrors) {
      const field = SERVER_FIELDS[item.field];
      if (!field) {
        other = true;
        continue;
      }
      errors[field] = [...errors[field], textOf(item.code, item.message)];
    }
    return other ? { ...errors, form: [t.signUp.failed] } : errors;
  }
  if (cause.code === 'AUTH_002') {
    return { ...NO_ERRORS, email: [textOf(cause.code, cause.message)] };
  }
  return { ...NO_ERRORS, form: [t.signUp.failed] };
}

/** BFF 가입 응답 dataBody(`@cf/api-client/server` 의 SignUpResult 와 같은 모양) */
type SignUpResult = {
  signedIn: boolean;
  loginRateLimit: { code: string | null; retryAfterSec: number | null } | null;
};

/**
 * 가입은 됐는데 자동 로그인을 못 했을 때 보낼 로그인 화면 경로(언어 접두어 없이).
 * 자동 로그인이 요청 제한이면 `limited` 에 Retry-After 초(모르면 빈 값), `reason` 에 그 코드를 싣는다
 * — 로그인 화면이 같은 안내 · 쿨다운을 잇는다(`readLoginRateLimit`).
 */
export function signedUpLoginPath(result: SignUpResult, returnPath: string): string {
  const query = new URLSearchParams({ signedUp: '1' });
  const limit = result.loginRateLimit;
  if (limit) {
    query.set('limited', String(limit.retryAfterSec ?? ''));
    if (limit.code) query.set('reason', limit.code);
  }
  if (returnPath !== '/') query.set('next', returnPath);
  return `/login?${query}`;
}
