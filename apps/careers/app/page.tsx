import { redirect } from 'next/navigation';

// The Skyzen marketing homepage was intentionally NOT copied. This is the
// root of the /careers app (basePath: '/careers' means this renders at
// anvicorp.com/careers). Redirect to the public openings board so /careers
// lands on something useful.
export default function CareersRoot() {
  redirect('/openings');
}
