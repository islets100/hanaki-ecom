import type { Metadata } from "next";
import "./globals.css";

const siteUrl = process.env.NEXT_PUBLIC_SITE_URL ?? "http://localhost:3000";

export const metadata: Metadata = {
  metadataBase: new URL(siteUrl),
  title: "花木商城｜多智能体客服电商平台",
  description: "完整 PC 电商体验与可追溯多智能体客服平台，覆盖商品、订单、物流、售后、投诉与人工接管。",
  openGraph: {
    title: "花木商城",
    description: "多智能体客服 · 全链路可追溯",
    type: "website",
    locale: "zh_CN",
    images: [{ url: "/og.png", width: 1920, height: 1003, alt: "花木商城多智能体客服平台" }],
  },
  twitter: { card: "summary_large_image", title: "花木商城", description: "多智能体客服 · 全链路可追溯", images: ["/og.png"] },
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return <html lang="zh-CN"><body>{children}</body></html>;
}
