(() => {
  if (window !== top || location.origin !== 'https://linux.do' ||
      !/^\/apps\/[0-9a-f-]{36}$/i.test(location.pathname) ||
      !window.KudoMiniAppAudio || window.__kudoMiniAppAudio) return;
  const bridge = window.KudoMiniAppAudio;
  const documentId = crypto.randomUUID();
  const showModal = HTMLDialogElement.prototype.showModal;
  const handled = new WeakSet();
  let pending, sequence = 0;
  const profiles = () => {
    const result = [{ action: '播放音频', notice: '此应用在本次运行中可播放声音。', muted: '静音进入', enabled: '开启声音' }];
    try {
      const node = document.querySelector('#data-preloaded, [data-preloaded]');
      const preload = JSON.parse(node?.getAttribute('data-preloaded') || node?.textContent || '{}');
      const value = preload['mini-app/production/' + location.pathname.split('/').pop() + '/'];
      const config = (typeof value === 'string' ? JSON.parse(value) : value)?.configuration;
      const native = config?.translations?.native;
      if (config?.channel === 'production' && native) result.push({ action: native.action?.audio?.play,
        notice: native.audioSessionNotice, muted: native.enterMuted, enabled: native.enableAudio });
    } catch { /* Unknown translations retain the website UI. */ }
    return result;
  };
  const buttonsFor = dialog => {
    if (!dialog.isConnected || dialog.ownerDocument !== document ||
        !dialog.classList.contains('mini-app-permission-dialog') || dialog.children.length !== 4) return;
    const [heading, action, preview, buttons] = dialog.children;
    const [muted, enabled] = buttons.children;
    if (heading.tagName !== 'H2' || !heading.id.startsWith('permission-') ||
        dialog.getAttribute('aria-labelledby') !== heading.id || action.tagName !== 'P' || preview.tagName !== 'P' ||
        !preview.classList.contains('mini-app-permission-dialog__preview') || buttons.tagName !== 'DIV' ||
        buttons.children.length !== 2 || muted.tagName !== 'BUTTON' || enabled.tagName !== 'BUTTON' ||
        !muted.classList.contains('button') || !enabled.classList.contains('--primary') || muted.disabled || enabled.disabled) return;
    if (!profiles().some(p => Object.values(p).every(v => typeof v === 'string' && v.length > 0) &&
        p.muted !== p.enabled && action.textContent === p.action && preview.textContent === p.notice &&
        muted.textContent === p.muted && enabled.textContent === p.enabled)) return;
    return { muted, enabled };
  };
  const post = (p, type) => bridge.postMessage(JSON.stringify({ type, document: documentId,
    id: p.id, url: p.url, appId: p.appId }));
  const restore = p => {
    if (p.opacity === undefined) return;
    if (p.opacity) p.dialog.style.setProperty('opacity', p.opacity, p.priority);
    else p.dialog.style.removeProperty('opacity');
  };
  const finish = (p, mode) => {
    if (pending !== p) return;
    pending = null;
    clearTimeout(p.timeout); clearInterval(p.watch); p.observer.disconnect();
    if (p.click) p.dialog.removeEventListener('click', p.click, true);
    restore(p);
    if (mode === 'fallback' && p.dialog.isConnected && location.href === p.url) {
      if (!p.dialog.open) showModal.call(p.dialog);
    } else if (mode === 'cancel' && p.dialog.isConnected) {
      // Reuse the site's original cancel path, including its executeCancel callback.
      p.dialog.dispatchEvent(new Event('cancel', { cancelable: true }));
    }
    try { post(p, 'finished'); } catch { /* The old document may no longer have a bridge. */ }
  };
  window.__kudoMiniAppAudio = (doc, id, command) => {
    const p = pending;
    if (!p || doc !== documentId || id !== p.id) return null;
    if (command === 'cancel') { finish(p, 'cancel'); return null; }
    if (command === 'fallback') { finish(p, 'fallback'); return null; }
    if (location.href !== p.url || !p.dialog.isConnected) { finish(p, 'cancel'); return null; }
    if (command === 'accepted') { clearTimeout(p.timeout); return null; }
    if (!['muted', 'enabled'].includes(command) || p.chosen) return null;
    const buttons = buttonsFor(p.dialog);
    if (!buttons || p.dialog.open) { finish(p, 'fallback'); return null; }
    p.chosen = true;
    const selected = buttons[command];
    p.opacity = p.dialog.style.getPropertyValue('opacity');
    p.priority = p.dialog.style.getPropertyPriority('opacity');
    p.dialog.style.setProperty('opacity', '0', 'important');
    p.click = event => {
      if (event.isTrusted && selected.contains(event.target)) queueMicrotask(() => finish(p, 'done'));
    };
    p.dialog.addEventListener('click', p.click, true);
    showModal.call(p.dialog);
    const rect = selected.getBoundingClientRect();
    const x = rect.x + rect.width / 2, y = rect.y + rect.height / 2;
    if (rect.width <= 0 || rect.height <= 0 || x < 0 || y < 0 || x >= innerWidth || y >= innerHeight ||
        !selected.contains(document.elementFromPoint(x, y))) { finish(p, 'fallback'); return null; }
    // Return only the identified original button. Native never accepts a generic click message.
    p.timeout = setTimeout(() => finish(p, 'fallback'), 1500);
    return [x, y, innerWidth, innerHeight];
  };
  HTMLDialogElement.prototype.showModal = function (...args) {
    if (handled.has(this) && pending?.dialog === this) return;
    if (this.open || handled.has(this) || pending || !/^\/apps\/[0-9a-f-]{36}$/i.test(location.pathname) || !buttonsFor(this))
      return showModal.apply(this, args);
    handled.add(this);
    const p = pending = { dialog: this, id: String(++sequence), url: location.href,
      appId: location.pathname.split('/').pop() };
    const check = () => {
      if (location.href !== p.url) finish(p, 'cancel');
      else if (!p.dialog.isConnected) finish(p, 'done');
    };
    p.observer = new MutationObserver(check);
    p.observer.observe(document, { childList: true, subtree: true });
    p.watch = setInterval(check, 250);
    p.timeout = setTimeout(() => finish(p, 'fallback'), 1500);
    try { post(p, 'request'); } catch { finish(p, 'fallback'); }
  };
  addEventListener('pagehide', () => { if (pending) finish(pending, 'cancel'); });
})();
