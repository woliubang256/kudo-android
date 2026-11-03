(() => {
  if (location.origin !== 'https://linux.do') return;
  const styleId = 'kudo-mini-app-presentation';
  const appId = url => url.origin === 'https://linux.do' && url.pathname.match(/^\/apps\/([a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12})\/?$/)?.[1];
  const frameStyle = doc => {
    if (!doc || doc.location.pathname !== '/mini-app-api/frame-host' || doc.__kudoMiniAppSized) return;
    doc.__kudoMiniAppSized = true;
    // The official frame host disallows new style sheets. Set layout properties through
    // the DOM API without changing its CSP or accessing the opaque application document.
    const size = () => {
      for (const element of [doc.documentElement, doc.body, ...doc.querySelectorAll('body>iframe')]) {
        if (!element) continue;
        for (const [property, value] of Object.entries({ width: '100%', height: '100%', margin: '0', padding: '0',
          display: 'block', 'min-height': '0', 'max-height': 'none', border: '0', overflow: 'hidden' }))
          element.style.setProperty(property, value, 'important');
      }
    };
    new MutationObserver(size).observe(doc, { childList: true, subtree: true });
    size();
  };
  if (window.top !== window) {
    // This is the same-origin host, never the opaque-origin application iframe.
    try {
      if (location.pathname === '/mini-app-api/frame-host' && window.frameElement?.classList.contains('mini-app-frame-host') &&
          appId(new URL(parent.location.href))) {
        frameStyle(document);
        addEventListener('DOMContentLoaded', () => frameStyle(document), { once: true });
      }
    } catch (_) {}
    return;
  }
  if (window.__kudoMiniAppPresentation) { window.__kudoMiniAppPresentation(); return; }
  let ancestors = [], reported = '', fallback, fallbackUrl = '';
  const clear = () => {
    document.documentElement.removeAttribute('data-kudo-mini-app');
    for (const element of ancestors) element.classList.remove('kudo-mini-app-path');
    ancestors = [];
  };
  const report = active => {
    const id = appId(new URL(location.href));
    if (!id || !window.KudoMiniApp) return;
    const key = `${location.href}:${active}`;
    if (reported === key) return;
    reported = key;
    window.KudoMiniApp.postMessage(JSON.stringify({ type: 'presentation', appId: id, url: location.href, active }));
  };
  const update = () => {
    if (!document.documentElement) return;
    clear();
    if (!appId(new URL(location.href))) { clearTimeout(fallback); fallback = undefined; reported = ''; return; }
    const loader = document.querySelector('.mini-app-page .mini-app-loader');
    if (!loader) {
      // SPA routes can remove the loader after pushState and after the load event.
      if (document.readyState === 'complete' && (!fallback || fallbackUrl !== location.href)) {
        clearTimeout(fallback);
        const expected = fallbackUrl = location.href;
        fallback = setTimeout(() => {
          fallback = undefined;
          if (location.href === expected && !document.querySelector('.mini-app-page .mini-app-loader')) report(false);
        }, 2000);
      }
      return;
    }
    clearTimeout(fallback); fallback = undefined;
    if (!document.getElementById(styleId)) {
      const style = document.createElement('style');
      style.id = styleId;
      style.textContent = `
        html[data-kudo-mini-app],html[data-kudo-mini-app] body{width:100%!important;height:100%!important;margin:0!important;padding:0!important;overflow:hidden!important}
        html[data-kudo-mini-app] .kudo-mini-app-path{display:block!important;position:static!important;transform:none!important;filter:none!important;perspective:none!important;contain:none!important;width:100%!important;height:100%!important;min-height:0!important;max-width:none!important;max-height:none!important;margin:0!important;padding:0!important}
        html[data-kudo-mini-app] .kudo-mini-app-path>:not(.kudo-mini-app-path):not(.mini-app-loader):not(dialog):not([role="dialog"]):not(.modal-container){display:none!important}
        html[data-kudo-mini-app] .mini-app-loader{position:fixed!important;inset:0!important;z-index:1;width:100%!important;height:100%!important;min-height:0!important;max-height:none!important;margin:0!important;padding:0!important;border:0!important;border-radius:0!important;background:var(--secondary,#fff)}
        html[data-kudo-mini-app] .mini-app-loader__viewport,html[data-kudo-mini-app] .mini-app-loader__frame,html[data-kudo-mini-app] .mini-app-frame-host{width:100%!important;height:100%!important;min-height:0!important;max-height:none!important;margin:0!important;padding:0!important;border:0!important;border-radius:0!important}
        html[data-kudo-mini-app] .mini-app-loader__loading{position:absolute!important;inset:0!important;height:100%!important;min-height:0!important}
        html[data-kudo-mini-app] .mini-app-loader__media{position:fixed;bottom:0;left:0;width:100%;max-height:50%;overflow:auto;z-index:2}
      `;
      document.documentElement.append(style);
    }
    for (let element = loader.parentElement; element && element !== document.documentElement; element = element.parentElement) {
      element.classList.add('kudo-mini-app-path'); ancestors.push(element);
    }
    document.documentElement.setAttribute('data-kudo-mini-app', '');
    for (const frame of loader.querySelectorAll('.mini-app-frame-host')) {
      try { frameStyle(frame.contentDocument); } catch (_) {}
    }
    report(true);
  };
  const settle = update;
  window.__kudoMiniAppPresentation = settle;
  const embedded = value => {
    if (value == null) return value;
    try { const url = new URL(value, location.href); if (appId(url)) { url.searchParams.set('embed_mode', 'true'); return url.href; } } catch (_) {}
    return value;
  };
  for (const method of ['pushState', 'replaceState']) {
    const original = history[method];
    history[method] = function(state, title, url) { const result = original.call(this, state, title, embedded(url)); settle(); return result; };
  }
  if (appId(new URL(location.href))) history.replaceState(history.state, '', embedded(location.href));
  new MutationObserver(update).observe(document, { childList: true, subtree: true });
  addEventListener('popstate', settle);
  addEventListener('DOMContentLoaded', settle, { once: true });
  addEventListener('load', settle, { once: true });
  if (document.readyState === 'complete') settle();
})();
