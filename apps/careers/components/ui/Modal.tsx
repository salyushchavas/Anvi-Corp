'use client';

import * as Dialog from '@radix-ui/react-dialog';
import { X } from 'lucide-react';
import { ReactNode } from 'react';
import { cn } from '@/lib/cn';

export interface ModalProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: ReactNode;
  description?: ReactNode;
  size?: 'sm' | 'md' | 'lg' | 'xl';
  footer?: ReactNode;
  children: ReactNode;
  /** Set true for a right-docked drawer-style modal. */
  docked?: 'right';
  className?: string;
}

const SIZES: Record<NonNullable<ModalProps['size']>, string> = {
  sm: 'max-w-md',
  md: 'max-w-lg',
  lg: 'max-w-2xl',
  xl: 'max-w-4xl',
};

export default function Modal({
  open,
  onOpenChange,
  title,
  description,
  size = 'md',
  footer,
  children,
  docked,
  className,
}: ModalProps) {
  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-40 bg-slate-900/50 animate-fade-in" />
        <Dialog.Content
          className={cn(
            'fixed z-50 flex flex-col bg-white shadow-2xl outline-none animate-modal-in',
            docked === 'right'
              ? 'right-0 top-0 h-full w-full max-w-md rounded-l-xl'
              : 'left-1/2 top-1/2 max-h-[85vh] w-[calc(100%-2rem)] -translate-x-1/2 -translate-y-1/2 rounded-xl',
            !docked && SIZES[size],
            className,
          )}
        >
          <header className="flex items-start justify-between gap-3 border-b border-slate-200 px-5 py-4">
            <div className="min-w-0 flex-1">
              <Dialog.Title className="text-base font-semibold text-slate-900">
                {title}
              </Dialog.Title>
              {description && (
                <Dialog.Description className="mt-0.5 text-xs text-slate-600">
                  {description}
                </Dialog.Description>
              )}
            </div>
            <Dialog.Close
              aria-label="Close"
              className="rounded-md p-1 text-slate-500 hover:bg-slate-100 hover:text-slate-700 focus-visible:ring-2 focus-visible:ring-brand-500"
            >
              <X className="h-4 w-4" strokeWidth={2} />
            </Dialog.Close>
          </header>
          <div className="flex-1 overflow-y-auto px-5 py-5">{children}</div>
          {footer && (
            <footer className="flex items-center justify-end gap-2 border-t border-slate-200 bg-slate-50 px-5 py-3">
              {footer}
            </footer>
          )}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
