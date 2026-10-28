'use client';

import { apiFetch } from '@cf/api-client';
import { Button, FieldError } from '@cf/ui';
import { useEffect, useState } from 'react';
import { useDictionary, useLocalePrefix } from '@/components/i18n/LocaleProvider';
import { hasRequiredAgreements, type SignUpAgreements } from '@/lib/auth/agreements';
import {
  SOCIAL_PROVIDERS,
  sessionFlowStorage,
  socialStartErrorText,
  startSocialLogin,
  type SocialProvider,
} from '@/lib/auth/social';
import { useRetryCooldown } from '@/lib/use-retry-cooldown';

/** 일반 BFF 프록시로 인가 URL 을 받는다(토큰이 없는 응답이라 전용 핸들러가 필요 없다) */
export function requestAuthorizeUrl(provider: SocialProvider): Promise<unknown> {
  return apiFetch<unknown>(`/api/v1/auth/oauth/${provider}/authorize-url`);
}

/** 제공자 인가 화면으로 간다 — 이 탭에서 이어져야 sessionStorage 의 흐름을 콜백이 읽는다 */
export function navigateToProvider(url: string): void {
  window.location.assign(url);
}

/**
 * 카카오 · 네이버로 계속하기 버튼(#25). 누르면 인가 URL 을 받아 흐름을 같은 탭 sessionStorage 에 두고 제공자로 간다(`startSocialLogin`).
 * `agreements` 를 주면(가입 화면) 필수 두 칸에 동의해야 시작하고 그 동의를 흐름에 담는다 — 못 했으면 `onAgreementsMissing`.
 * 로그인 화면은 동의 없이 시작하고, 신규 가입이면 콜백 화면이 동의를 받아 다시 시작한다.
 * 색은 제공자 브랜드 색 대신 보조 버튼(토큰) 그대로다 — 임의 색 금지(styling-theme.md §2).
 */
export function SocialLoginButtons({
  returnPath,
  heading,
  agreements,
  onAgreementsMissing,
}: {
  returnPath: string;
  heading: string;
  agreements?: SignUpAgreements;
  onAgreementsMissing?: () => void;
}) {
  const t = useDictionary().auth;
  const localePrefix = useLocalePrefix();
  const [pending, setPending] = useState<SocialProvider | null>(null);
  const [error, setError] = useState<string | null>(null);
  const cooldown = useRetryCooldown();

  // 제공자 화면에서 뒤로 와 bfcache 로 되살아나면 "이동 중" 인 채로 멈춰 있지 않게 푼다
  useEffect(() => {
    const onShow = (event: PageTransitionEvent) => {
      if (event.persisted) setPending(null);
    };
    window.addEventListener('pageshow', onShow);
    return () => window.removeEventListener('pageshow', onShow);
  }, []);

  const start = async (provider: SocialProvider) => {
    if (pending || cooldown.coolingDown) return;
    if (agreements && !hasRequiredAgreements(agreements)) {
      setError(null);
      onAgreementsMissing?.();
      return;
    }
    setPending(provider);
    setError(null);
    try {
      await startSocialLogin({
        provider,
        returnPath,
        localePrefix,
        agreements: agreements ?? null,
        storage: sessionFlowStorage(),
        requestAuthorizeUrl,
        navigate: navigateToProvider,
      });
    } catch (cause) {
      setPending(null);
      cooldown.startFrom(cause);
      setError(socialStartErrorText(cause, t));
    }
  };

  return (
    <section aria-label={heading} className="flex flex-col gap-3">
      <p className="text-center text-sm text-muted-fg">{heading}</p>
      {SOCIAL_PROVIDERS.map((provider) => (
        <Button
          key={provider}
          variant="secondary"
          disabled={pending !== null || cooldown.coolingDown}
          onClick={() => void start(provider)}
          className="h-12 w-full"
        >
          {pending === provider ? t.social.starting : t.social.providers[provider]}
        </Button>
      ))}
      <FieldError messages={error ? [error] : []} />
    </section>
  );
}
