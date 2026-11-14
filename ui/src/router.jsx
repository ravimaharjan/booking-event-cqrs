import React, { useEffect, useState } from 'react';

// A tiny hash router: pages live at /#/path?query. Hash URLs need no server-side fallback, so they work
// the same under the Vite dev server and behind nginx.
const read = () => {
  const raw = window.location.hash.replace(/^#/, '') || '/';
  const [path, query = ''] = raw.split('?');
  return { path, params: new URLSearchParams(query) };
};

export function useLocation() {
  const [loc, setLoc] = useState(read);
  useEffect(() => {
    const onChange = () => setLoc(read());
    window.addEventListener('hashchange', onChange);
    return () => window.removeEventListener('hashchange', onChange);
  }, []);
  return loc;
}

export const navigate = (to) => {
  window.location.hash = to;
};

export function Link({ to, children, ...rest }) {
  return (
    <a href={`#${to}`} {...rest}>
      {children}
    </a>
  );
}

export function NavLink({ to, path, children }) {
  const active = to === '/' ? path === '/' : path.startsWith(to);
  return (
    <Link to={to} className={active ? 'nav active' : 'nav'}>
      {children}
    </Link>
  );
}
