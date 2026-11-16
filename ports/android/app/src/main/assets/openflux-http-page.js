(function () {
  'use strict';
  // SOURCE ONLY: future host installs this before provider HTML, with an exact
  // synthetic origin and one main-frame MessagePort. No direct fetch fallback.
  const requests = new Map();
  const seen = new Set();
  let port = null, retired = false, reserved = 0, staged = false, pendingStage = null;
  const idPattern = /^[0-9a-f]{32}$/;
  const utf8 = new TextEncoder();
  function wipe(job) { if (job.bytes) job.bytes.fill(0); job.bytes = null; }
  function cancelBody(job) {
    if (job.bodyTimer !== null) clearTimeout(job.bodyTimer); job.bodyTimer = null;
    const reader = job.reader; job.reader = null;
    if (reader) { try { Promise.resolve(reader.cancel()).catch(() => {}); } catch (_) {} }
    if (job.upload) job.upload.fill(0); job.upload = null;
  }
  function release(job) {
    cancelBody(job);
    if (job.signal && job.abort) job.signal.removeEventListener('abort', job.abort);
    job.signal = null; job.abort = null;
    wipe(job); job.frame = null; reserved -= job.reserved; job.reserved = 0;
  }
  function fail(id) {
    const job = requests.get(id);
    if (!job) return;
    requests.delete(id); release(job); job.reject(new TypeError('OpenFlux page request unavailable'));
  }
  function stop() {
    if (retired) return;
    retired = true; pendingStage = null;
    for (const id of Array.from(requests.keys())) fail(id);
    if (port) port.close(); port = null;
  }
  function unbase64(text, limit) {
    if (typeof text !== 'string' || text.length > Math.ceil(limit / 3) * 4 || text.length % 4) throw new Error();
    const raw = atob(text);
    if (raw.length > limit || btoa(raw) !== text) throw new Error();
    const bytes = new Uint8Array(raw.length);
    for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);
    return bytes;
  }
  function base64(bytes) {
    let text = '';
    for (let i = 0; i < bytes.length; i++) text += String.fromCharCode(bytes[i]);
    return btoa(text);
  }
  function receive(event) {
    if (retired) return;
    if (event.ports && event.ports.length) { event.ports.forEach(p => p.close()); stop(); return; }
    let frame, id, job;
    try {
      if (typeof event.data !== 'string' || event.data.length > 24576) throw new Error();
      frame = JSON.parse(event.data);
      if (!Array.isArray(frame) || typeof frame[1] !== 'string' || !idPattern.test(frame[1])) throw new Error();
      id = frame[1]; job = requests.get(id);
      if (!job) return; // Retired/aborted request, never attach it to a new promise.
      if (frame[0] === 'error' && frame.length === 3) { fail(id); return; }
      if (job.hashing) throw new Error();
      if (frame[0] === 'meta' && frame.length === 6) {
        if (job.bytes || !Number.isInteger(frame[2]) || frame[2] < 200 || frame[2] > 599 ||
            !Number.isInteger(frame[4]) || frame[4] < 0 || frame[4] > 524288 ||
            typeof frame[5] !== 'string' || !/^[0-9a-f]{64}$/.test(frame[5]) || !Array.isArray(frame[3])) throw new Error();
        // Redirect following, cookies and compressed-content decoding are NOT emulated.
        if ((frame[2] >= 300 && frame[2] <= 399) || ((job.method === 'HEAD' || frame[2] === 204) && frame[4] !== 0)) throw new Error();
        job.headers = new Headers();
        if (frame[3].length > 512) throw new Error();
        for (const pair of frame[3]) {
          if (!Array.isArray(pair) || pair.length !== 2 || typeof pair[0] !== 'string' || typeof pair[1] !== 'string') throw new Error();
          if (/^(set-cookie|set-cookie2)$/i.test(pair[0])) continue;
          job.headers.append(pair[0], pair[1]);
        }
        job.status = frame[2]; job.hash = frame[5]; job.bytes = new Uint8Array(frame[4]); job.offset = 0;
      } else if (frame[0] === 'chunk' && frame.length === 4) {
        if (!job.bytes || frame[2] !== job.offset || job.offset % 8192) throw new Error();
        const bytes = unbase64(frame[3], 8192);
        try {
          if (bytes.length !== Math.min(8192, job.bytes.length - job.offset) || !bytes.length) throw new Error();
          job.bytes.set(bytes, job.offset); job.offset += bytes.length;
        } finally { bytes.fill(0); }
      } else if (frame[0] === 'end' && frame.length === 2) {
        if (!job.bytes || job.offset !== job.bytes.length) throw new Error();
        job.hashing = true;
        crypto.subtle.digest('SHA-256', job.bytes).then(digest => {
          if (retired || requests.get(id) !== job) return;
          const actual = Array.from(new Uint8Array(digest), b => b.toString(16).padStart(2, '0')).join('');
          if (actual !== job.hash) { fail(id); return; }
          try {
            const response = new Response((job.method === 'HEAD' || job.status === 204) ? null : job.bytes,
              { status: job.status, headers: job.headers });
            requests.delete(id); release(job); job.resolve(response);
          } catch (_) { fail(id); }
        }, () => fail(id));
      } else throw new Error();
    } catch (_) { if (id && job) fail(id); else stop(); }
  }
  async function bodyBytes(request, job, id) {
    if (!request.body) return new Uint8Array();
    const reader = request.body.getReader();
    // Reserve the full bounded body BEFORE reading; never retain an array per
    // producer chunk (including arbitrarily many zero-length chunks).
    job.reader = reader; job.upload = new Uint8Array(65536);
    const bytes = job.upload; let size = 0, reads = 0;
    job.bodyTimer = setTimeout(() => fail(id), 10000);
    try {
      while (true) {
        if (++reads > 1024) throw new Error(); // Also bound zero/tiny-chunk producer work.
        const part = await reader.read();
        if (retired || requests.get(id) !== job) throw new Error();
        if (part.done) break;
        if (!(part.value instanceof Uint8Array) || size + part.value.length > 65536) throw new Error();
        bytes.set(part.value, size); size += part.value.length;
      }
      return bytes.subarray(0, size);
    } catch (_) { cancelBody(job); throw new TypeError('OpenFlux page body unavailable'); }
    finally {
      if (job.bodyTimer !== null) clearTimeout(job.bodyTimer); job.bodyTimer = null;
      if (job.reader === reader) job.reader = null;
      try { reader.releaseLock(); } catch (_) {}
    }
  }
  function fetchViaPort(input, init) {
    if (retired || requests.size >= 8 || seen.size >= 58)
      return Promise.reject(new TypeError('OpenFlux page request unavailable'));
    let request, headers, overhead, reservation;
    try {
      request = new Request(input, init);
      const url = new URL(request.url);
      if (request.url.length > 8192 || url.origin !== location.origin || url.username || url.password || url.hash ||
          !['GET','HEAD','POST','PUT','PATCH','DELETE','OPTIONS'].includes(request.method) ||
          request.credentials === 'include' || request.signal.aborted) throw new Error();
      headers = [];
      let headerBytes = 0;
      for (const pair of request.headers.entries()) {
        if (headers.length >= 32) throw new Error();
        if (pair[0].length > 8192 || pair[1].length > 8192) throw new Error();
        headerBytes += utf8.encode(pair[0]).length + utf8.encode(pair[1]).length + 4;
        if (headerBytes > 8192) throw new Error();
        headers.push(pair);
      }
      const urlBytes = utf8.encode(request.url).length;
      if (urlBytes > 8192) throw new Error();
      overhead = urlBytes + headerBytes;
      reservation = overhead + (request.body ? 65536 : 0);
      if (reserved + reservation > 131072) throw new Error();
    } catch (_) { return Promise.reject(new TypeError('OpenFlux page request unavailable')); }
    const random = crypto.getRandomValues(new Uint8Array(16));
    const id = Array.from(random, b => b.toString(16).padStart(2, '0')).join('');
    if (seen.has(id)) return Promise.reject(new TypeError('OpenFlux page request unavailable'));
    seen.add(id);
    return new Promise((resolve, reject) => {
      const job = { resolve, reject, method: request.method, bytes: null, hashing: false,
        reserved: reservation, frame: null, upload: null, reader: null, bodyTimer: null,
        signal: request.signal, abort: null };
      reserved += reservation;
      requests.set(id, job);
      // Abort is local retirement only. It cannot revoke an already admitted handler.
      job.abort = () => fail(id);
      request.signal.addEventListener('abort', job.abort, { once: true });
      if (request.signal.aborted) { fail(id); return; }
      bodyBytes(request, job, id).then(bytes => {
        try {
          if (retired || requests.get(id) !== job) return;
          const size = bytes.length + overhead;
          if (size > job.reserved) throw new Error();
          const frame = JSON.stringify(['request', id, request.method, request.url, headers, base64(bytes)]);
          if (utf8.encode(frame).length > 114688) throw new Error();
          reserved -= job.reserved - size; job.reserved = size;
          // Provider scripts commonly fetch during initial parsing, before host
          // port delivery. Bound these requests; never call original fetch.
          if (port) port.postMessage(frame); else job.frame = frame;
        } catch (_) { fail(id); } finally {
          bytes.fill(0); if (job.upload) job.upload.fill(0); job.upload = null;
        }
      }, () => fail(id));
    });
  }
  Object.defineProperty(window, 'fetch', { configurable: false, writable: false, value: fetchViaPort });
  Object.defineProperty(window, 'openfluxSubmit', { configurable: false, writable: false, value: function (value) {
    if (retired || staged) return;
    let payload;
    try { payload = JSON.stringify(value); if (typeof payload !== 'string' || utf8.encode(payload).length > 8192) return; }
    catch (_) { return; }
    staged = true;
    const frame = JSON.stringify(['stage', payload]);
    if (port) { try { port.postMessage(frame); } catch (_) { stop(); } } else pendingStage = frame;
  } });
  window.addEventListener('message', function (event) {
    if (retired || port || (event.source !== null && event.source !== window) ||
        event.data !== 'mcrf-http-channel' || !event.ports || event.ports.length !== 1) return;
    port = event.ports[0]; port.onmessage = receive; port.onmessageerror = stop; port.start();
    for (const [id, job] of requests) if (job.frame !== null) {
      try { port.postMessage(job.frame); job.frame = null; } catch (_) { fail(id); }
    }
    if (pendingStage !== null) { try { port.postMessage(pendingStage); pendingStage = null; } catch (_) { stop(); return; } }
    window.dispatchEvent(new Event('openflux-ready'));
  });
  window.addEventListener('pagehide', stop, { once: true });
  Object.defineProperty(window, '__mcrfHttpBridgeReady', { configurable: false, writable: false, value: true });
})();
