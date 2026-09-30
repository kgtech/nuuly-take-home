import '@testing-library/jest-dom/vitest';
import { afterAll, afterEach, beforeAll, beforeEach } from 'vitest';
import { cleanup, configure } from '@testing-library/react';
import { server, store } from './server';

// A cold first render on a loaded machine can exceed the 1000 ms default findBy/waitFor timeout.
configure({ asyncUtilTimeout: 5000 });

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
beforeEach(() => store.reset());
afterEach(() => {
  server.resetHandlers();
  cleanup();
  window.location.hash = '';
});
afterAll(() => server.close());
