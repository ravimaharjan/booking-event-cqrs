import React, { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import * as api from './api.js';

const load = (key, fallback) => {
  try {
    return JSON.parse(localStorage.getItem(key)) ?? fallback;
  } catch {
    return fallback;
  }
};
const save = (key, value) => {
  try {
    localStorage.setItem(key, JSON.stringify(value));
  } catch {
    /* storage unavailable: the page still works */
  }
};

const pushRecent = (list, id) => [id, ...list.filter((x) => x !== id)].slice(0, 20);

const SessionContext = createContext(null);
export const useSession = () => useContext(SessionContext);

// State shared by every page: the read-after-write token, what the user has created, and the request log.
export function SessionProvider({ children }) {
  const [apiUp, setApiUp] = useState(null);
  const [token, setToken] = useState(load('token', null)); // { value, issuedAt }
  const [log, setLog] = useState([]);
  const [last, setLast] = useState(null);
  const [recent, setRecent] = useState(load('recent', { events: [], bookings: [] }));
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const check = () => api.health().then(setApiUp);
    check();
    const t = setInterval(check, 2000);
    return () => clearInterval(t);
  }, []);

  const remember = useCallback((kind, id) => {
    setRecent((r) => {
      const next = { ...r, [kind]: pushRecent(r[kind], id) };
      save('recent', next);
      return next;
    });
  }, []);

  const record = useCallback((r) => {
    setLast(r);
    setLog((l) => [r, ...l].slice(0, 100));
    if (r.issuedToken) {
      const t = { value: r.issuedToken, issuedAt: Date.now() };
      setToken(t);
      save('token', t);
    }
    return r;
  }, []);

  const readToken = token ? token.value : undefined;

  const run = useCallback(
    async (fn) => {
      setBusy(true);
      try {
        return record(await fn());
      } catch (e) {
        alert(`Request failed: ${e.message}`);
        return null;
      } finally {
        setBusy(false);
      }
    },
    [record],
  );

  const value = useMemo(
    () => ({
      apiUp, token, readToken, log, setLog, last, setLast, recent, remember, record, run, busy,
    }),
    [apiUp, token, readToken, log, last, recent, remember, record, run, busy],
  );
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}
