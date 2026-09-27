import '@testing-library/jest-dom/vitest';
import { afterAll, afterEach, beforeAll, beforeEach } from 'vitest';
import { cleanup } from '@testing-library/react';
import { server, store } from './server';

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
beforeEach(() => store.reset());
afterEach(() => {
  server.resetHandlers();
  cleanup();
  window.location.hash = '';
});
afterAll(() => server.close());
