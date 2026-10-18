import { brand } from '@cf/brand';
import type { Metadata } from 'next';
import type { ReactNode } from 'react';
import { SiteHeader } from '@/components/header/SiteHeader';
import './globals.css';

export const metadata: Metadata = {
  title: { default: brand.name, template: brand.seo.titleTemplate },
  description: brand.seo.description,
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang={brand.defaultLocale === 'ko-KR' ? 'ko' : 'en'}>
      <body className="bg-bg font-sans text-fg antialiased">
        <SiteHeader brandName={brand.name} navigation={brand.navigation} />
        {children}
      </body>
    </html>
  );
}
