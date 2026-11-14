import React from 'react';
import { NavLink, useLocation } from './router.jsx';
import { SessionProvider, useSession } from './session.jsx';
import TokenBar from './TokenBar.jsx';
import Inspector from './Inspector.jsx';
import Home from './pages/Home.jsx';
import EventPage from './pages/EventPage.jsx';
import BookingsPage from './pages/BookingsPage.jsx';

function Shell() {
  const { apiUp } = useSession();
  const { path, params } = useLocation();

  let page;
  const event = path.match(/^\/events\/([^/]+)$/);
  if (event) page = <EventPage key={event[1]} eventId={event[1]} />;
  else if (path === '/bookings') page = <BookingsPage key={path + params} initialId={params.get('id') || ''} eventFilter={params.get('event') || ''} />;
  else page = <Home bookEventId={params.get('book') || ''} />;

  return (
    <div className="app">
      <header>
        <h1>Event Booking · CQRS</h1>
        <nav>
          <NavLink to="/" path={path}>Events &amp; booking</NavLink>
          <NavLink to="/bookings" path={path}>Bookings</NavLink>
        </nav>
        <div className="head-right">
          <span className={`dot ${apiUp ? 'up' : 'down'}`} />
          {apiUp === null ? 'checking API…' : apiUp ? 'API up' : 'API down (start it: scripts/start.sh)'}
        </div>
      </header>
      <TokenBar />
      <div className="layout">
        <main>{page}</main>
        <aside>
          <Inspector />
        </aside>
      </div>
    </div>
  );
}

export default function App() {
  return (
    <SessionProvider>
      <Shell />
    </SessionProvider>
  );
}
