'use client';

import { ApiError, apiFetch } from '@cf/api-client';
import { Button, Checkbox, FieldError, TextField } from '@cf/ui';
import { useRouter } from 'next/navigation';
import { useState, type ChangeEvent, type FormEvent } from 'react';
import { useDictionary, useLocalizedHref } from '@/components/i18n/LocaleProvider';
import { Link } from '@/components/i18n/Link';
import type { Dictionary } from '@/lib/i18n/dictionaries';

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

type Agreements = { termsOfService: boolean; privacyPolicy: boolean; marketing: boolean };

/**
 * 이메일 회원가입 폼(#25). BFF `POST /api/v1/auth/sign-up` 이 가입 뒤 같은 이메일 · 비밀번호로 로그인까지 해 쿠키를 심는다.
 * `signedIn` 이면 돌아갈 경로로, 자동 로그인을 못 했으면(계정은 만들어짐) 가입 완료 안내와 함께 로그인 화면으로 보낸다.
 * 형식 검증은 BE 가 정본이다 — 화면은 필수 · 길이만 브라우저에 맡기고, 비밀번호 확인 일치만 직접 본다(BE 에 없는 칸).
 */
export function SignUpForm({ returnPath }: { returnPath: string }) {
  const t = useDictionary().auth;
  const localize = useLocalizedHref();
  const router = useRouter();
  const [submitting, setSubmitting] = useState(false);
  const [errors, setErrors] = useState<SignUpErrors>(NO_ERRORS);
  const [agreements, setAgreements] = useState<Agreements>({
    termsOfService: false,
    privacyPolicy: false,
    marketing: false,
  });
  const allAgreed = agreements.termsOfService && agreements.privacyPolicy && agreements.marketing;

  const onSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (submitting) return;
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
      const result = await apiFetch<{ signedIn: boolean }>('/api/v1/auth/sign-up', {
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
        const query = new URLSearchParams({ signedUp: '1' });
        if (returnPath !== '/') query.set('next', returnPath);
        router.replace(localize(`/login?${query}`));
      }
    } catch (cause) {
      setSubmitting(false);
      setErrors(signUpErrors(cause, t));
    }
  };

  const toggle = (key: keyof Agreements) => (event: ChangeEvent<HTMLInputElement>) => {
    const checked = event.currentTarget.checked;
    setAgreements((current) => ({ ...current, [key]: checked }));
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

        <fieldset className="mt-3 flex flex-col gap-3">
          <legend className="sr-only">{t.signUp.agreements}</legend>
          <Checkbox
            label={<span className="font-medium">{t.signUp.agreeAll}</span>}
            checked={allAgreed}
            onChange={(event) => {
              const checked = event.currentTarget.checked;
              setAgreements({
                termsOfService: checked,
                privacyPolicy: checked,
                marketing: checked,
              });
            }}
            errors={errors.agreements}
          />
          <div className="flex flex-col gap-3 pl-7">
            <Checkbox
              label={t.signUp.termsOfService}
              name="termsOfService"
              checked={agreements.termsOfService}
              onChange={toggle('termsOfService')}
              required
              errors={errors.termsOfService}
            />
            <Checkbox
              label={t.signUp.privacyPolicy}
              name="privacyPolicy"
              checked={agreements.privacyPolicy}
              onChange={toggle('privacyPolicy')}
              required
              errors={errors.privacyPolicy}
            />
            <Checkbox
              label={t.signUp.marketing}
              name="marketing"
              checked={agreements.marketing}
              onChange={toggle('marketing')}
              errors={errors.marketing}
            />
          </div>
        </fieldset>

        {/* 폼 전체 오류는 버튼 바로 위 — 누른 자리 가까이에서 읽힌다 */}
        <FieldError messages={errors.form} />
        <Button type="submit" disabled={submitting} className="h-12 w-full tracking-[0.15em]">
          {submitting ? t.signUp.submitting : t.signUp.submit}
        </Button>
      </form>

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
 * 그 밖의 실패(연결 · 일시 장애 · 예상 밖)는 한 문구로 다시 시도를 권한다.
 */
export function signUpErrors(cause: unknown, t: Dictionary['auth']): SignUpErrors {
  if (!(cause instanceof ApiError)) return { ...NO_ERRORS, form: [t.signUp.failed] };
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
