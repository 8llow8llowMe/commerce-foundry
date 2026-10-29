'use client';

import { apiFetch } from '@cf/api-client';
import { Button, FieldError } from '@cf/ui';
import { useRouter } from 'next/navigation';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useDictionary, useLocalePrefix, useLocalizedHref } from '@/components/i18n/LocaleProvider';
import { Link } from '@/components/i18n/Link';
import { hasRequiredAgreements, NO_AGREEMENTS, type SignUpAgreements } from '@/lib/auth/agreements';
import {
  SOCIAL_ACCOUNTS_PATH,
  callbackPurpose,
  runSocialLinkCallback,
  type SocialLinkBody,
  type SocialLinkFailure,
} from '@/lib/auth/social-link';
import {
  runSocialCallback,
  sessionFlowStorage,
  socialStartErrorText,
  startSocialLogin,
  type SocialFlow,
  type SocialLoginBody,
  type SocialProvider,
} from '@/lib/auth/social';
import type { Dictionary } from '@/lib/i18n/dictionaries';
import { useRetryCooldown } from '@/lib/use-retry-cooldown';
import { AgreementsFieldset, type AgreementErrors } from './AgreementsFieldset';
import { SocialLinkFailureView } from './SocialLinkFailureView';
import { navigateToProvider, requestAuthorizeUrl } from './SocialLoginButtons';

type View =
  | { kind: 'working' }
  | { kind: 'agreements'; flow: SocialFlow }
  | { kind: 'error'; message: string; returnPath: string }
  /** 계정 연결(#356) 콜백 실패 */
  | { kind: 'link'; failure: SocialLinkFailure };

/** BFF 소셜 로그인 — 토큰은 httpOnly 쿠키로 심기고 본문에는 없다 */
function sendSocialLogin(provider: SocialProvider, body: SocialLoginBody): Promise<null> {
  return apiFetch<null>(`/api/v1/auth/oauth/${provider}/login`, { method: 'POST', json: body });
}

/** 연결 완료 — 일반 BFF 프록시가 고객 access 쿠키를 Bearer 로 붙인다. 응답(연결 목록)에 토큰은 없다 */
function sendSocialLink(provider: SocialProvider, body: SocialLinkBody): Promise<unknown> {
  return apiFetch<unknown>(`${SOCIAL_ACCOUNTS_PATH}/${provider}`, { method: 'POST', json: body });
}

/** 다시 시작할 로그인 화면(돌아갈 경로를 잇는다) */
export function restartHref(returnPath: string): string {
  return returnPath === '/' ? '/login' : `/login?next=${encodeURIComponent(returnPath)}`;
}

/**
 * 제공자 콜백(`/login/oauth/{provider}`). 브라우저에서만 처리한다 — sessionStorage 의 흐름과 콜백 쿼리를 맞춰 본다.
 * BE 는 소셜 로그인(#25)과 계정 연결(#356)에 같은 redirect URI 를 쓰므로 먼저 저장한 흐름의 state 로 목적을 가른다(`callbackPurpose`).
 * 연결 흐름이 없으면 기존 로그인 콜백 그대로다.
 * 처리 순서(언어 넘기기 → 쿼리 지우기 → 흐름 지우기 → 보내기 → 이동)는 `runSocialCallback` · `runSocialLinkCallback` 이 정한다 — 이 컴포넌트는 부르고 결과를 그린다.
 * StrictMode 의 effect 이중 실행은 ref(`started`)를 가드로 넘겨 한 번만 보낸다.
 * 로그인 성공은 돌아갈 경로로, 연결 성공은 연결 화면으로 바꾸고 서버 컴포넌트를 다시 그린다. 신규 가입 동의(AUTH_056)면 동의를 받아 인가부터 다시 시작한다.
 */
export function SocialCallback({ provider }: { provider: SocialProvider }) {
  const t = useDictionary().auth;
  const prefix = useLocalePrefix();
  const localize = useLocalizedHref();
  const router = useRouter();
  const [view, setView] = useState<View>({ kind: 'working' });
  const started = useRef(false);

  useEffect(() => {
    const storage = sessionFlowStorage();
    const common = {
      provider,
      prefix,
      storage,
      location: window.location,
      history: window.history,
      localize,
      navigate: (href: string) => {
        router.replace(href);
        router.refresh();
      },
      t,
    };
    if (callbackPurpose(storage, window.location.search) === 'link') {
      void runSocialLinkCallback({ ...common, send: sendSocialLink }, started).then((result) => {
        if (!result || result.kind === 'handoff' || result.kind === 'success') return;
        setView({ kind: 'link', failure: result });
      });
      return;
    }
    void runSocialCallback({ ...common, send: sendSocialLogin }, started).then((result) => {
      if (!result || result.kind === 'handoff' || result.kind === 'success') return;
      if (result.kind === 'agreements') {
        setView({ kind: 'agreements', flow: result.flow });
        return;
      }
      setView({
        kind: 'error',
        message: result.message,
        returnPath: result.flow?.returnPath ?? '/',
      });
    });
  }, [provider, prefix, localize, router, t]);

  if (view.kind === 'link') {
    return <SocialLinkFailureView failure={view.failure} />;
  }
  if (view.kind === 'agreements') {
    return <SocialAgreements flow={view.flow} />;
  }
  if (view.kind === 'error') {
    return (
      <div className="flex flex-col gap-8">
        <p role="alert" className="text-center text-sm">
          {view.message}
        </p>
        <RestartLink returnPath={view.returnPath} label={t.social.restart} />
      </div>
    );
  }
  // 로그인인지 연결인지는 effect 에서 정해지므로 둘 다에 맞는 안내다
  return (
    <p role="status" className="text-center text-sm text-muted-fg">
      {t.social.working}
    </p>
  );
}

function RestartLink({ returnPath, label }: { returnPath: string; label: string }) {
  return (
    <Link
      href={restartHref(returnPath)}
      className="inline-flex h-12 w-full items-center justify-center rounded-md bg-primary px-4 text-sm font-medium text-primary-fg"
    >
      {label}
    </Link>
  );
}

const NO_AGREEMENT_ERRORS: AgreementErrors = {
  agreements: [],
  termsOfService: [],
  privacyPolicy: [],
  marketing: [],
};

/** 필수 칸 중 빠진 것에 가입 폼과 같은 문구(AUTH_111 · AUTH_112)를 붙인다 */
export function missingAgreementErrors(
  value: SignUpAgreements,
  t: Dictionary['auth'],
): AgreementErrors {
  return {
    ...NO_AGREEMENT_ERRORS,
    termsOfService: value.termsOfService ? [] : [t.errors.AUTH_111 ?? t.signUp.failed],
    privacyPolicy: value.privacyPolicy ? [] : [t.errors.AUTH_112 ?? t.signUp.failed],
  };
}

/**
 * 신규 가입 약관 동의(AUTH_056 뒤). 앞 흐름의 state 는 이미 쓰였으므로 동의를 흐름에 담아 인가부터 다시 시작한다
 * — 두 번째 콜백이 agreements 를 함께 보낸다. 필수 두 칸에 동의하지 않으면 진행하지 않는다.
 */
function SocialAgreements({ flow }: { flow: SocialFlow }) {
  const t = useDictionary().auth;
  const prefix = useLocalePrefix();
  const [agreements, setAgreements] = useState<SignUpAgreements>(NO_AGREEMENTS);
  const [errors, setErrors] = useState<AgreementErrors>(NO_AGREEMENT_ERRORS);
  const [formError, setFormError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const cooldown = useRetryCooldown();

  const onSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (pending || cooldown.coolingDown) return;
    if (!hasRequiredAgreements(agreements)) {
      setErrors(missingAgreementErrors(agreements, t));
      return;
    }
    setErrors(NO_AGREEMENT_ERRORS);
    setFormError(null);
    setPending(true);
    try {
      await startSocialLogin({
        provider: flow.provider,
        returnPath: flow.returnPath,
        localePrefix: prefix,
        agreements,
        storage: sessionFlowStorage(),
        requestAuthorizeUrl,
        navigate: navigateToProvider,
      });
    } catch (cause) {
      setPending(false);
      cooldown.startFrom(cause);
      setFormError(socialStartErrorText(cause, t));
    }
  };

  return (
    <div className="flex flex-col gap-8">
      <form onSubmit={onSubmit} className="flex flex-col gap-5">
        <h2 className="text-lg font-medium">{t.social.consentTitle}</h2>
        <p className="text-sm text-muted-fg">{t.social.consentDescription}</p>
        <AgreementsFieldset value={agreements} onChange={setAgreements} errors={errors} />
        <FieldError messages={formError ? [formError] : []} />
        <Button
          type="submit"
          disabled={pending || cooldown.coolingDown}
          className="h-12 w-full tracking-[0.15em]"
        >
          {pending ? t.social.starting : t.social.consentSubmit}
        </Button>
      </form>
      <p className="text-center text-sm">
        <Link
          href={restartHref(flow.returnPath)}
          className="text-muted-fg underline underline-offset-4"
        >
          {t.social.restart}
        </Link>
      </p>
    </div>
  );
}
