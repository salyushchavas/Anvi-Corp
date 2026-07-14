'use client';

import * as RadixTabs from '@radix-ui/react-tabs';
import { ReactNode } from 'react';
import { cn } from '@/lib/cn';

export interface TabDef {
  key: string;
  label: string;
  count?: number | string;
  disabled?: boolean;
}

export interface TabsProps {
  tabs: TabDef[];
  activeKey: string;
  onChange: (key: string) => void;
  children?: ReactNode;
  className?: string;
}

export default function Tabs({
  tabs,
  activeKey,
  onChange,
  children,
  className,
}: TabsProps) {
  return (
    <RadixTabs.Root
      value={activeKey}
      onValueChange={onChange}
      className={cn('w-full', className)}
    >
      <RadixTabs.List
        className="flex flex-wrap items-center gap-1 border-b border-slate-200"
        aria-label="Tabs"
      >
        {tabs.map((t) => (
          <RadixTabs.Trigger
            key={t.key}
            value={t.key}
            disabled={t.disabled}
            className={cn(
              '-mb-px inline-flex items-center gap-1.5 border-b-2 border-transparent px-3 py-2 text-sm font-medium text-slate-600 transition-colors',
              'hover:text-slate-900',
              'data-[state=active]:border-brand-700 data-[state=active]:text-brand-700',
              'disabled:cursor-not-allowed disabled:opacity-50',
              'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500 focus-visible:ring-offset-2',
            )}
          >
            {t.label}
            {t.count != null && (
              <span className="inline-flex items-center justify-center rounded-full bg-slate-100 px-1.5 py-0.5 text-[10px] font-semibold text-slate-700">
                {t.count}
              </span>
            )}
          </RadixTabs.Trigger>
        ))}
      </RadixTabs.List>
      <div className="mt-5">{children}</div>
    </RadixTabs.Root>
  );
}

export const TabPanel = RadixTabs.Content;
