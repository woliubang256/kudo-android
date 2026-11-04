(() => {
  const path = location.pathname;
  if (location.origin !== 'https://linux.do' ||
      !(path === '/apps' || path.startsWith('/apps/') || path === '/mini-app-api/frame-host')) return;
  const bridge = window.KudoMiniApp;
  if (!bridge || window.__kudoMiniAppCompat) return;
  window.__kudoMiniAppCompat = true;
  const waiting = new Map();
  const documentId = crypto.randomUUID();
  const error = code => Object.assign(new Error(code), { code });
  const topUrl = () => { try { return top.location.href; } catch { return ''; } };
  const eligible = () => location.origin === 'https://linux.do' &&
    (location.pathname === '/apps' || location.pathname.startsWith('/apps/') || location.pathname === '/mini-app-api/frame-host');
  let sequence = 0;
  let executing = null;
  let watchTimer;
  const observer = new MutationObserver(() => checkWaiting());
  const finish = (id, value, code, cancelNative = false) => {
    const pending = waiting.get(id);
    if (!pending) return;
    waiting.delete(id);
    clearTimeout(pending.timeout);
    if (!waiting.size) { clearInterval(watchTimer); observer.disconnect(); }
    if (cancelNative) {
      try { bridge.postMessage(JSON.stringify({ type: 'clipboard-cancel', document: documentId, id, error: code })); }
      catch { /* The document may already have lost its native bridge. Still settle its promise. */ }
    }
    if (code) pending.reject(error(code)); else pending.resolve(value);
  };
  const checkWaiting = () => {
    for (const [id, pending] of waiting) {
      if (!eligible() || topUrl() !== pending.url || (pending.dialog && !pending.dialog.isConnected))
        finish(id, null, 'APP_UNLOADED', true);
    }
  };
  bridge.onmessage = event => {
    let reply;
    try { reply = JSON.parse(event.data); } catch { return; }
    checkWaiting();
    finish(reply.id, reply.value, reply.error);
  };
  const request = (method, text) => new Promise((resolve, reject) => {
    if (!eligible()) { reject(error('APP_UNLOADED')); return; }
    const id = String(++sequence);
    const url = topUrl();
    const timeout = setTimeout(() => finish(id, null, 'TIMEOUT', true), 60000);
    const dialog = executing?.method === method ? executing.dialog : null;
    waiting.set(id, { resolve, reject, timeout, url, dialog });
    if (waiting.size === 1) {
      observer.observe(document, { childList: true, subtree: true });
      watchTimer = setInterval(checkWaiting, 250);
    }
    try { bridge.postMessage(JSON.stringify({ type: 'clipboard', document: documentId, id, url, method, text })); }
    catch { finish(id, null, 'NATIVE_ACTION_FAILED'); }
  });
  const clipboard = navigator.clipboard || {};
  const nativeRead = typeof clipboard.readText === 'function' ? clipboard.readText.bind(clipboard) : null;
  const nativeWrite = typeof clipboard.writeText === 'function' ? clipboard.writeText.bind(clipboard) : null;
  // Always request native consent in mini apps, even if this WebView supports clipboard APIs.
  const read = clipboard.readText = () => eligible() ? request('read') :
    nativeRead ? nativeRead() : Promise.reject(error('NOT_SUPPORTED'));
  const write = clipboard.writeText = text => {
    if (!eligible()) return nativeWrite ? nativeWrite(text) : Promise.reject(error('NOT_SUPPORTED'));
    if (typeof text !== 'string' || new TextEncoder().encode(text).length > 4096)
      return Promise.reject(error('INVALID_ARGUMENTS'));
    return request('write', text);
  };
  if (!navigator.clipboard) Object.defineProperty(navigator, 'clipboard', { value: clipboard });

  const profiles = () => {
    // Verified site labels also cover SPA entry when the initial document has no app preload.
    const result = [{ read: '读取剪贴板文字', write: '写入剪贴板文字', cancel: '取消', allow: '仅本次允许' }];
    try {
      const preloaded = JSON.parse(document.getElementById('data-preloaded')?.textContent || '{}');
      for (const [key, value] of Object.entries(preloaded)) {
        if (!key.startsWith('mini-app/production/')) continue;
        const config = (typeof value === 'string' ? JSON.parse(value) : value)?.configuration;
        const native = config?.translations?.native;
        if (config?.channel === 'production' && native) result.push({
          read: native.action?.clipboard?.readText, write: native.action?.clipboard?.writeText,
          cancel: native.rejectAction, allow: native.allowAction,
        });
      }
    } catch { /* Unknown configuration keeps the site's original permission UI. */ }
    return result;
  };
  const showModal = HTMLDialogElement.prototype.showModal;
  const handled = new WeakSet();
  HTMLDialogElement.prototype.showModal = function (...args) {
    if (!eligible() || !this.isConnected || this.open || this.ownerDocument !== document ||
        !this.classList.contains('mini-app-permission-dialog') || this.children.length !== 4 ||
        clipboard.readText !== read || clipboard.writeText !== write) return showModal.apply(this, args);
    if (handled.has(this)) return;
    const [heading, action, preview, buttons] = this.children;
    const [cancel, allow] = buttons.children;
    if (heading.tagName !== 'H2' || !heading.id.startsWith('permission-') ||
        this.getAttribute('aria-labelledby') !== heading.id || action.tagName !== 'P' || preview.tagName !== 'P' ||
        !preview.classList.contains('mini-app-permission-dialog__preview') || buttons.tagName !== 'DIV' ||
        buttons.children.length !== 2 || cancel.tagName !== 'BUTTON' || allow.tagName !== 'BUTTON' ||
        !cancel.classList.contains('button') || !allow.classList.contains('--primary') || cancel.disabled || allow.disabled)
      return showModal.apply(this, args);
    const profile = profiles().find(p => typeof p.read === 'string' && typeof p.write === 'string' &&
      p.read !== p.write && cancel.textContent === p.cancel && allow.textContent === p.allow &&
      (action.textContent === p.read || action.textContent === p.write));
    if (!profile) return showModal.apply(this, args);
    handled.add(this);
    // Advance only this clipboard callback to our wrapped API. No browser activation or grant is created.
    executing = { dialog: this, method: action.textContent === profile.read ? 'read' : 'write' };
    try { allow.click(); } finally { executing = null; }
  };
  addEventListener('pagehide', () => {
    for (const id of waiting.keys()) finish(id, null, 'APP_UNLOADED', true);
  });
})();
