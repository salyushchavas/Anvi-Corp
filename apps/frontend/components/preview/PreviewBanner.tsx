import { Sparkles } from 'lucide-react';

export default function PreviewBanner() {
  return (
    <div className="flex items-center gap-2 border-b border-amber-200 bg-amber-50 px-4 py-2 md:px-8">
      <Sparkles className="h-4 w-4 shrink-0 text-amber-600" strokeWidth={2} />
      <span className="text-xs font-medium text-amber-800">
        PREVIEW — This module is under active development. Sample data shown.
      </span>
    </div>
  );
}
