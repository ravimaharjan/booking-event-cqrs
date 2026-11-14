export const money = (cents) => (cents / 100).toLocaleString(undefined, { style: 'currency', currency: 'EUR' });
export const when = (iso) => (iso ? new Date(iso).toLocaleString() : '');
