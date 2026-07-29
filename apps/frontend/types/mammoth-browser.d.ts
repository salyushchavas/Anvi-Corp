// Type shim for the `mammoth.browser.js` subpath — the shipped .d.ts
// only covers the main (Node) entry. The browser build is a pre-bundled
// self-contained variant we import dynamically to keep the docx→HTML
// converter out of the initial JS payload.
declare module 'mammoth/mammoth.browser' {
  interface ConvertOptions {
    arrayBuffer?: ArrayBuffer;
    buffer?: ArrayBuffer;
    styleMap?: string | string[];
    includeDefaultStyleMap?: boolean;
    includeEmbeddedStyleMap?: boolean;
  }
  interface ConvertResult {
    value: string;
    messages: Array<{ type: string; message: string }>;
  }
  export function convertToHtml(input: ConvertOptions): Promise<ConvertResult>;
  export function extractRawText(input: ConvertOptions): Promise<ConvertResult>;
}
