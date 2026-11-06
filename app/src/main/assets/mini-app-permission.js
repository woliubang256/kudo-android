(() => {
  if (window !== top || location.origin !== 'https://linux.do' ||
      !/^\/apps\/[0-9a-f-]{36}$/i.test(location.pathname) ||
      !window.KudoMiniAppPermission || window.__kudoMiniAppPermission) return;
  const bridge = window.KudoMiniAppPermission;
  const documentId = crypto.randomUUID();
  const showModal = HTMLDialogElement.prototype.showModal;
  const handled = new WeakSet();
  let pending, sequence = 0;
  const profiles = () => {
    const result = [{ audio: '播放音频', notice: '此应用在本次运行中可播放声音。', muted: '静音进入', enabled: '开启声音',
      video: '播放视频', fullscreen: '进入全屏', reject: '取消', allow: '仅本次允许' }];
    try {
      const node = document.querySelector('#data-preloaded, [data-preloaded]');
      const preload = JSON.parse(node?.getAttribute('data-preloaded') || node?.textContent || '{}');
      const value = preload['mini-app/production/' + location.pathname.split('/').pop() + '/'];
      const config = (typeof value === 'string' ? JSON.parse(value) : value)?.configuration;
      const native = config?.translations?.native;
      if (config?.channel === 'production' && native) result.push({ audio: native.action?.audio?.play,
        notice: native.audioSessionNotice, muted: native.enterMuted, enabled: native.enableAudio,
        video: native.action?.video?.play, fullscreen: native.action?.fullscreen?.request,
        reject: native.rejectAction, allow: native.allowAction });
    } catch { /* Unknown translations retain the website UI. */ }
    return result;
  };
  const describe = dialog => {
    if (!dialog.isConnected || dialog.ownerDocument !== document ||
        !dialog.classList.contains('mini-app-permission-dialog') || dialog.children.length !== 4) return;
    const [heading, action, preview, buttons] = dialog.children;
    const [deny, allow] = buttons.children;
    if (heading.tagName !== 'H2' || !heading.id.startsWith('permission-') ||
        dialog.getAttribute('aria-labelledby') !== heading.id || action.tagName !== 'P' || preview.tagName !== 'P' ||
        !preview.classList.contains('mini-app-permission-dialog__preview') || buttons.tagName !== 'DIV' ||
        buttons.children.length !== 2 || deny.tagName !== 'BUTTON' || allow.tagName !== 'BUTTON' ||
        !deny.classList.contains('button') || !allow.classList.contains('--primary') || deny.disabled || allow.disabled) return;
    const matches = (label, reject, accept) => typeof label === 'string' && label.length > 0 &&
      typeof reject === 'string' && reject.length > 0 && typeof accept === 'string' && accept.length > 0 && reject !== accept &&
      action.textContent === label && deny.textContent === reject && allow.textContent === accept;
    for (const p of profiles()) {
      if (matches(p.audio, p.muted, p.enabled) && typeof p.notice === 'string' && preview.textContent === p.notice)
        return { deny, allow, kind: 'audio', preview: '' };
      if (matches(p.fullscreen, p.reject, p.allow) && preview.textContent === '')
        return { deny, allow, kind: 'fullscreen', preview: '' };
      if (matches(p.video, p.reject, p.allow) && preview.textContent.trim() && preview.textContent.length <= 200)
        return { deny, allow, kind: 'video', preview: preview.textContent };
    }
  };
  const post = (p, type) => bridge.postMessage(JSON.stringify({ type, document: documentId,
    id: p.id, url: p.url, appId: p.appId, kind: p.kind, preview: p.preview }));
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
  window.__kudoMiniAppPermission = (doc, id, command) => {
    const p = pending;
    if (!p || doc !== documentId || id !== p.id) return null;
    if (command === 'cancel') { finish(p, 'cancel'); return null; }
    if (command === 'fallback') { finish(p, 'fallback'); return null; }
    if (location.href !== p.url || !p.dialog.isConnected) { finish(p, 'cancel'); return null; }
    if (command === 'accepted') { clearTimeout(p.timeout); return null; }
    if (!['deny', 'allow'].includes(command) || p.chosen) return null;
    const buttons = describe(p.dialog);
    if (!buttons || buttons.kind !== p.kind || buttons.preview !== p.preview || p.dialog.open) { finish(p, 'fallback'); return null; }
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
    if (this.open || handled.has(this) || pending || !/^\/apps\/[0-9a-f-]{36}$/i.test(location.pathname))
      return showModal.apply(this, args);
    const info = describe(this);
    if (!info) return showModal.apply(this, args);
    handled.add(this);
    const p = pending = { dialog: this, id: String(++sequence), url: location.href,
      appId: location.pathname.split('/').pop(), kind: info.kind, preview: info.preview };
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
