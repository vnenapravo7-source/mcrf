// PREPARED ONLY. Run with the final common verification, never per-edit.
// Production asset in a VM with synthetic Request/readers/timers/port. NOT a
// browser/WebView/CSP/real Streams/Android/native/provider acceptance receipt.
import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { readFileSync } from 'node:fs';

const source = readFileSync(new URL('../../../app/src/main/assets/openflux-http-page.js', import.meta.url), 'utf8');
const origin = 'https://' + 'a'.repeat(32) + '.openflux.invalid';
const emptyHash = 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855';
const flush = async () => { for (let i = 0; i < 256; i++) await Promise.resolve(); };
function harness() {
  const listeners = new Map(), timers = new Map(), frames = [];
  let sequence = 0, timerId = 0;
  const window = { addEventListener: (key, fn) => listeners.set(key, fn), dispatchEvent() {} };
  const port = { close() {}, start() {}, postMessage: frame => frames.push(JSON.parse(frame)) };
  const context = vm.createContext({ window, location: { origin }, URL, TextEncoder, Uint8Array,
    Headers, Response, Promise, Event, atob, btoa,
    Request: function (value) { return value; },
    crypto: { getRandomValues: bytes => { bytes.fill(0); bytes[15] = ++sequence; return bytes; },
      subtle: { digest: async () => Uint8Array.from(emptyHash.match(/../g), x => parseInt(x, 16)).buffer } },
    setTimeout: callback => { timers.set(++timerId, callback); return timerId; },
    clearTimeout: id => timers.delete(id) });
  new vm.Script(source, { filename: 'openflux-http-page.js' }).runInContext(context);
  function request(reader = null, headers = []) {
    const signal = { aborted: false, listeners: new Set(),
      addEventListener(_, fn) { this.listeners.add(fn); },
      removeEventListener(_, fn) { this.listeners.delete(fn); },
      abort() { this.aborted = true; for (const fn of [...this.listeners]) fn(); } };
    return { url: origin + '/body', method: reader ? 'POST' : 'GET', credentials: 'omit',
      signal, headers: { entries: () => headers.values() }, body: reader ? { getReader: () => { reader.opens++; return reader; } } : null };
  }
  function start(value) {
    // Attach a rejection handler immediately, including intentionally pending jobs.
    const result = { value: null, error: null };
    result.promise = window.fetch(value).then(value => { result.value = value; }, error => { result.error = error; });
    return result;
  }
  function connect() { listeners.get('message')({ source: null, data: 'mcrf-http-channel', ports: [port] }); }
  function stop() { listeners.get('pagehide')(); }
  return { request, start, connect, stop, frames, port, timers };
}
function reader(parts = null) {
  let position = 0;
  return { opens: 0, reads: 0, cancels: 0, releases: 0,
    read() { this.reads++; return parts === null ? new Promise(() => {}) : Promise.resolve(
      position < parts.length ? { done: false, value: parts[position++] } : { done: true }); },
    cancel() { this.cancels++; return Promise.resolve(); }, releaseLock() { this.releases++; } };
}

test('body reservation rejects a second full pending body before opening its reader; abort frees capacity', async () => {
  const h = harness(), one = reader(), two = reader(), three = reader();
  try {
    const firstRequest = h.request(one), first = h.start(firstRequest), second = h.start(h.request(two));
    await flush();
    assert.equal(one.opens, 1); assert.equal(two.opens, 0); assert.ok(second.error);
    firstRequest.signal.abort(); await flush(); assert.ok(first.error); assert.equal(one.cancels, 1);
    h.start(h.request(three)); await flush(); assert.equal(three.opens, 1);
  } finally { h.stop(); }
});

test('abort cancels a pending reader and removes its listener without awaiting producer cancellation', async () => {
  const h = harness(), stream = reader();
  stream.cancel = function () { this.cancels++; return new Promise(() => {}); };
  const request = h.request(stream), result = h.start(request);
  try {
    request.signal.abort(); await flush();
    assert.ok(result.error); assert.equal(stream.cancels, 1);
    assert.equal(request.signal.listeners.size, 0); assert.equal(h.timers.size, 0);
  } finally { h.stop(); }
});

test('fixed body timer retires a stalled read without native traffic', async () => {
  const h = harness(), stream = reader(), result = h.start(h.request(stream));
  try {
    assert.equal(h.timers.size, 1); [...h.timers.values()][0](); await flush();
    assert.ok(result.error); assert.equal(stream.cancels, 1); assert.equal(h.timers.size, 0);
    h.connect(); assert.equal(h.frames.length, 0);
  } finally { h.stop(); }
});

test('oversized producer chunk is rejected and reader cancelled before a request frame', async () => {
  const h = harness(), stream = reader([new Uint8Array(65537)]), result = h.start(h.request(stream));
  try {
    await flush(); h.connect();
    assert.ok(result.error); assert.equal(stream.cancels, 1); assert.equal(stream.releases, 1);
    assert.equal(h.frames.length, 0); assert.equal(h.timers.size, 0);
  } finally { h.stop(); }
});

test('zero-length producer chunks have a finite read-operation budget', async () => {
  const h = harness(), stream = reader();
  stream.read = function () { this.reads++; return Promise.resolve({ done: false, value: new Uint8Array() }); };
  const result = h.start(h.request(stream));
  try {
    // Explicit bounded microtask drain, no wall-clock timer or external IO.
    for (let i = 0; i < 8192 && result.error === null; i++) await Promise.resolve();
    assert.ok(result.error); assert.equal(stream.reads, 1024); assert.equal(stream.cancels, 1);
    h.connect(); assert.equal(h.frames.length, 0);
  } finally { h.stop(); }
});

test('successful empty response detaches the request abort listener', async () => {
  const h = harness(), request = h.request(), result = h.start(request);
  try {
    h.connect(); await flush(); assert.equal(h.frames.length, 1);
    const id = h.frames[0][1];
    h.port.onmessage({ data: JSON.stringify(['meta', id, 200, [], 0, emptyHash]) });
    h.port.onmessage({ data: JSON.stringify(['end', id]) }); await flush();
    assert.ok(result.value); assert.equal(result.error, null); assert.equal(request.signal.listeners.size, 0);
    request.signal.abort(); assert.ok(result.value);
  } finally { h.stop(); }
});

test('bounded 64KiB stream still produces one exact binary POST frame', async () => {
  const h = harness(), parts = Array.from({ length: 8 }, () => new Uint8Array(8192).fill(71));
  const stream = reader(parts), request = h.request(stream, [['content-type', 'application/octet-stream']]);
  const result = h.start(request);
  try {
    await flush(); h.connect(); assert.equal(h.frames.length, 1);
    const frame = h.frames[0]; assert.equal(frame[0], 'request'); assert.equal(frame[2], 'POST');
    assert.equal(frame[3], request.url); assert.equal(frame[4][0][0], 'content-type');
    const bytes = Buffer.from(frame[5], 'base64');
    assert.equal(bytes.length, 65536); assert.ok(bytes.every(value => value === 71)); bytes.fill(0);
    assert.equal(stream.releases, 1); assert.equal(h.timers.size, 0);
    assert.ok(parts.every(part => part.every(value => value === 71))); // Producer-owned chunks unchanged.
    request.signal.abort(); await flush(); assert.ok(result.error);
  } finally { h.stop(); }
});

test('page retirement cancels pending readers and prevents queued page requests from being posted', async () => {
  const h = harness(), stream = reader(), request = h.request(stream), result = h.start(request);
  h.start(h.request()); // Also owns a pre-port completed GET frame after the microtask drain.
  try {
    await flush(); h.stop(); await flush(); h.connect();
    assert.ok(result.error); assert.equal(stream.cancels, 1); assert.equal(request.signal.listeners.size, 0);
    assert.equal(h.timers.size, 0); assert.equal(h.frames.length, 0);
  } finally { h.stop(); }
});
