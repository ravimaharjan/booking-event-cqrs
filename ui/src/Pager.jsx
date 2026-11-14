import React from 'react';

// Server-side paging: the list endpoints return a bare page, so "has next" is inferred from a full page.
export default function Pager({ page, size, setSize, count, onPage, disabled }) {
  const hasNext = count === size;
  return (
    <div className="pager">
      <button className="ghost" disabled={disabled || page === 0} onClick={() => onPage(page - 1, size)}>
        ← Prev
      </button>
      <span>Page {page + 1}</span>
      <button className="ghost" disabled={disabled || !hasNext} onClick={() => onPage(page + 1, size)}>
        Next →
      </button>
      {setSize && (
        <label className="hint">
          per page{' '}
          <select value={size} onChange={(e) => { setSize(Number(e.target.value)); onPage(0, Number(e.target.value)); }}>
            {[5, 10, 20, 50].map((n) => <option key={n}>{n}</option>)}
          </select>
        </label>
      )}
    </div>
  );
}
