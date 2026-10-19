import { brand } from '@cf/brand';
import type { Metadata } from 'next';
import type { ReactNode } from 'react';
import { SiteFooter } from '@/components/footer/SiteFooter';
import { SiteHeader } from '@/components/header/SiteHeader';
import { businessInfo } from '@/lib/env';
import './globals.css';

export const metadata: Metadata = {
  title: { default: brand.name, template: brand.seo.titleTemplate },
  description: brand.seo.description,
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang={brand.defaultLocale === 'ko-KR' ? 'ko' : 'en'}>
      {/* 내용이 짧은 페이지에서도 푸터가 화면 아래에 붙게 한다 */}
      <body className="flex min-h-dvh flex-col bg-bg font-sans text-fg antialiased">
        <SiteHeader brandName={brand.name} navigation={brand.navigation} />
        <div className="flex-1">{children}</div>
        <SiteFooter brandName={brand.name} links={brand.footerLinks} business={businessInfo} />
      </body>
    </html>
  );
}
