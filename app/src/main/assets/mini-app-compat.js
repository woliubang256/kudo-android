(() => {
  const path = location.pathname;
  if (location.origin !== 'https://linux.do' ||
      !(path === '/apps' || path.startsWith('/apps/') || path === '/mini-app-api/frame-host')) return;
  const bridge = window.KudoMiniApp;
  if (!bridge || window.__kudoMiniAppCompat) return;
  window.__kudoMiniAppCompat = true;
  const waiting = new Map();
  let sequence = 0;
  bridge.onmessage = event => {
    let reply;
    try { reply = JSON.parse(event.data); } catch { return; }
    const pending = waiting.get(reply.id);
    if (!pending) return;
    waiting.delete(reply.id);
    clearTimeout(pending.timeout);
    if (reply.error) pending.reject(Object.assign(new Error(reply.error), { code: reply.error }));
    else pending.resolve(reply.value);
  };
  const request = (method, text) => new Promise((resolve, reject) => {
    const id = String(++sequence);
    const timeout = setTimeout(() => {
      waiting.delete(id);
      reject(Object.assign(new Error('TIMEOUT'), { code: 'TIMEOUT' }));
    }, 60000);
    waiting.set(id, { resolve, reject, timeout });
    bridge.postMessage(JSON.stringify({ type: 'clipboard', id, method, text }));
  });
  const clipboard = navigator.clipboard || {};
  const nativeRead = typeof clipboard.readText === 'function' ? clipboard.readText.bind(clipboard) : null;
  const nativeWrite = typeof clipboard.writeText === 'function' ? clipboard.writeText.bind(clipboard) : null;
  const fallback = (error, method, text) => {
    if (error.name === 'NotAllowedError' || error.name === 'NotSupportedError') return request(method, text);
    throw error;
  };
  clipboard.readText = () => nativeRead ? nativeRead().catch(error => fallback(error, 'read')) : request('read');
  clipboard.writeText = text => {
    if (typeof text !== 'string' || new TextEncoder().encode(text).length > 4096)
      return Promise.reject(Object.assign(new Error('INVALID_ARGUMENTS'), { code: 'INVALID_ARGUMENTS' }));
    return nativeWrite ? nativeWrite(text).catch(error => fallback(error, 'write', text)) : request('write', text);
  };
  if (!navigator.clipboard) Object.defineProperty(navigator, 'clipboard', { value: clipboard });
  addEventListener('pagehide', () => {
    for (const pending of waiting.values()) {
      clearTimeout(pending.timeout);
      pending.reject(Object.assign(new Error('APP_UNLOADED'), { code: 'APP_UNLOADED' }));
    }
    waiting.clear();
  }, { once: true });
})();
