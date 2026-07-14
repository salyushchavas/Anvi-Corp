import { ReactNode } from 'react';
import PreviewBanner from './PreviewBanner';

/**
 * Wraps preview page content so the banner sits flush under the topbar,
 * cancelling DashboardLayout's main padding for the banner row, then
 * re-applying it inside for the actual content.
 */
export default function PreviewShell({ children }: { children: ReactNode }) {
  return (
    <div className="-m-4 md:-m-8">
      <PreviewBanner />
      <div className="p-4 md:p-8">{children}</div>
    </div>
  );
}
