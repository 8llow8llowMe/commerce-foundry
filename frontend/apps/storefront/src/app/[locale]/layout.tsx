import { brand } from '@cf/brand';
import type { Metadata } from 'next';
import { notFound } from 'next/navigation';
import { SiteFooter } from '@/components/footer/SiteFooter';
import { SiteHeader } from '@/components/header/SiteHeader';
import { LocaleProvider } from '@/components/i18n/LocaleProvider';
import { businessInfo, siteUrl } from '@/lib/env';
import { hasLocale, localeRouting } from '@/lib/i18n/config';
import { getDictionary } from '@/lib/i18n/dictionaries';
import { localePrefix, localeUrl } from '@/lib/i18n/routing';
import { getI18n, localeHomeHref } from '@/lib/i18n/server';
import '../globals.css';

// 언어 접두어(/en)든 언어 호스트든 proxy 가 /[locale]/... 로 바꿔 들어온다 (lib/i18n/routing.ts)
export function generateStaticParams() {
  return brand.locales.map((locale) => ({ locale }));
}

export async function generateMetadata(): Promise<Metadata> {
  const { locale, content } = await getI18n();
  return {
    // 상대 경로 메타(canonical · OG 이미지)를 이 언어의 정식 주소로 푼다
    metadataBase: new URL(localeUrl(localeRouting, locale, '/', siteUrl)),
    title: { default: brand.name, template: content.seo.titleTemplate },
    description: content.seo.description,
  };
}

export default async function LocaleLayout({ children, params }: LayoutProps<'/[locale]'>) {
  const { locale } = await params;
  if (!hasLocale(locale)) notFound();
  const dict = getDictionary(locale);
  const content = brand.content[locale];
  const languages = brand.locales.map((code) => ({
    locale: code,
    name: getDictionary(code).languageName,
    href: localeHomeHref(code),
  }));

  return (
    <html lang={locale}>
      {/* 내용이 짧은 페이지에서도 푸터가 화면 아래에 붙게 한다 */}
      <body className="flex min-h-dvh flex-col bg-bg font-sans text-fg antialiased">
        <LocaleProvider locale={locale} prefix={localePrefix(localeRouting, locale)} dict={dict}>
          <SiteHeader
            brandName={brand.name}
            navigation={content.navigation}
            labels={dict.header}
            languages={languages}
          />
          <div className="flex-1">{children}</div>
          <SiteFooter
            brandName={brand.name}
            links={content.footerLinks}
            business={businessInfo}
            labels={dict.footer}
          />
        </LocaleProvider>
      </body>
    </html>
  );
}
