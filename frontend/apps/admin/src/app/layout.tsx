import { brand } from '@cf/brand';
import type { Metadata } from 'next';
import type { ReactNode } from 'react';
import './globals.css';

export const metadata: Metadata = {
  title: { default: `${brand.name} 어드민`, template: `%s | ${brand.name} 어드민` },
  robots: { index: false, follow: false },
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="ko">
      <body className="bg-bg font-sans text-fg antialiased">{children}</body>
    </html>
  );
}
