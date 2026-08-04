'use client';

import { forwardRef, useCallback, useState, type InputHTMLAttributes } from 'react';
import { Eye, EyeOff } from 'lucide-react';

type NativeInputProps = Omit<InputHTMLAttributes<HTMLInputElement>, 'type'>;

export interface PasswordInputProps extends NativeInputProps {
  /**
   * Classes applied to the underlying <input>. Keep whatever the surrounding
   * page uses so borders, focus rings, error states, and autofill visuals
   * remain unchanged; we tack on right-padding for the icon.
   */
  className?: string;
  /** Optional classes on the wrapper <div> (defaults to just `relative`). */
  wrapperClassName?: string;
}

const PasswordInput = forwardRef<HTMLInputElement, PasswordInputProps>(
  function PasswordInput({ className = '', wrapperClassName = '', ...rest }, ref) {
    const [visible, setVisible] = useState(false);
    const toggle = useCallback(() => setVisible((v) => !v), []);

    return (
      <div className={`relative ${wrapperClassName}`.trim()}>
        <input
          {...rest}
          ref={ref}
          type={visible ? 'text' : 'password'}
          className={`${className} pr-10`.trim()}
        />
        <button
          type="button"
          onClick={toggle}
          aria-label={visible ? 'Hide password' : 'Show password'}
          aria-pressed={visible}
          tabIndex={0}
          className="absolute inset-y-0 right-0 flex items-center px-3 text-gray-500 hover:text-gray-700 focus:outline-none focus-visible:ring-1 focus-visible:ring-accent rounded"
        >
          {visible ? (
            <EyeOff className="h-4 w-4" aria-hidden="true" />
          ) : (
            <Eye className="h-4 w-4" aria-hidden="true" />
          )}
        </button>
      </div>
    );
  },
);

export default PasswordInput;
