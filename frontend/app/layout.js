import OtelProvider from './otel-provider';

export default function RootLayout({ children }) {
  return (
    <html lang="en">
      <body>
        <OtelProvider>
          <main>{children}</main>
        </OtelProvider>
      </body>
    </html>
  );
} 