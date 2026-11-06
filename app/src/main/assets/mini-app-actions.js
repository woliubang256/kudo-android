(() => {
  if (window.top !== window || location.origin !== 'https://linux.do' || window.__kudoMiniAppAction) return;
  const appId = () => location.pathname.match(/^\/apps\/([a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12})\/?$/)?.[1];
  const originalFetch = window.fetch;
  let running;
  let route = appId(), generation = 0;
  const navigate = () => {
    const next = appId();
    if (next !== route) { route = next; running = undefined; generation++; }
  };
  for (const method of ['pushState', 'replaceState']) {
    const original = history[method];
    history[method] = function(...args) { const result = original.apply(this, args); navigate(); return result; };
  }
  addEventListener('popstate', navigate);
  // Keep only the first authorized release of this running app. Token renewal may
  // authorize a newer release, which must never become the target of this report.
  window.fetch = function(input, options) {
    const id = appId(), address = location.href;
    navigate();
    const epoch = generation;
    const result = originalFetch.call(this, input, options);
    try {
      const url = new URL(typeof input === 'string' || input instanceof URL ? input : input.url, location.href);
      if (id && url.origin === location.origin && url.pathname === `/mini-app-api/production/${id}/launch`) {
        result.then(async response => {
          if (!response.ok) return;
          const release = (await response.clone().json()).release;
          if (generation === epoch && location.href === address && release?.app_id === id && release.channel === 'production' &&
              Number.isSafeInteger(release.id) && release.id > 0 && running?.appId !== id)
            running = { appId: id, releaseId: release.id };
        }).catch(() => {});
      }
    } catch (_) {}
    return result;
  };
  const failure = (message, uncertain = false) => Object.assign(new Error(message), { uncertain });
  const request = async (path, method = 'GET', body, valid = () => true) => {
    let response, sent = false;
    try {
      const headers = { Accept: 'application/json', 'X-Requested-With': 'XMLHttpRequest' };
      if (method !== 'GET') {
        const csrf = await originalFetch('/session/csrf', { credentials: 'same-origin', redirect: 'error', headers, signal: AbortSignal.timeout(12000) });
        if (!csrf.ok) throw failure('暂时无法确认登录状态，请重试');
        const token = (await csrf.json()).csrf;
        if (typeof token !== 'string' || !token) throw failure('请先登录后再操作');
        headers['X-CSRF-Token'] = token;
        headers['Content-Type'] = 'application/json';
      }
      if (!valid()) throw failure('页面已切换，请重新操作');
      sent = method !== 'GET';
      response = await originalFetch(path, { method, credentials: 'same-origin', redirect: 'error', headers,
        body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(12000) });
    } catch (error) {
      if (error.uncertain !== undefined) throw error;
      throw failure('网络连接失败，请稍后重试', sent);
    }
    let data;
    try { data = await response.json(); } catch (_) {
      throw failure(response.ok ? method === 'GET' ? '加载结果暂不可用，请重试' : '提交结果尚未确认，请到网站核对' :
        '网页验证或服务暂不可用，请到网站核对', method !== 'GET');
    }
    if (!response.ok) {
      const message = Array.isArray(data.errors) ? data.errors.filter(e => typeof e === 'string').join('；') : data.error;
      throw failure(typeof message === 'string' && message ? message.slice(0, 500) :
        response.status === 429 ? '操作过于频繁，请稍后重试' : response.status === 401 ? '请先登录后再操作' : '当前操作暂不可用', method !== 'GET' && response.status >= 500);
    }
    return data;
  };
  const flags = async () => {
    const site = await request('/site.json');
    let preload = document.querySelector('[data-preloaded]')?.getAttribute('data-preloaded') || document.getElementById('data-preloaded')?.textContent;
    if (!preload) {
      const response = await originalFetch('/latest', { credentials: 'same-origin', redirect: 'error' });
      if (!response.ok) throw failure('举报配置加载失败，请重试');
      const doc = new DOMParser().parseFromString(await response.text(), 'text/html');
      preload = doc.querySelector('[data-preloaded]')?.getAttribute('data-preloaded') || doc.getElementById('data-preloaded')?.textContent;
    }
    const data = JSON.parse(preload || '{}');
    const settings = typeof data.siteSettings === 'string' ? JSON.parse(data.siteSettings) : data.siteSettings;
    const minimum = settings?.min_personal_message_post_length;
    if (!Number.isInteger(minimum) || minimum < 1 || minimum > 500) throw failure('暂时无法读取举报字数配置');
    return { minimum, reasons: (site.post_action_types || []).filter(reason => reason.enabled === true &&
      reason.name_key !== 'notify_user' && reason.applies_to?.includes('DiscourseMiniApp::App')).sort((a,b) => a.position - b.position)
      .slice(0, 40).map(reason => ({ id: reason.id, key: String(reason.name_key || '').slice(0, 100),
        name: String(reason.name || '').slice(0, 100), description: String(reason.description || '').slice(0, 400),
        require_message: reason.require_message === true })) };
  };
  window.__kudoMiniAppAction = async (token, operation, payload) => {
    const id = appId(), address = location.href, bridge = window.KudoMiniApp;
    if (!id || !bridge || typeof token !== 'string') return;
    const path = `/mini-app-api/production/${id}`;
    const valid = () => location.href === address && appId() === id;
    try {
      let result;
      if (operation === 'context') {
        const data = await request(`${path}/info`);
        if (data.app?.app_id !== id) throw failure('应用信息与当前页面不符');
        result = { app: { app_id: id, can_rate: data.app.can_rate === true, can_tip: data.app.can_tip === true,
          can_report: data.app.can_report === true, my_rating: data.app.my_rating, rating_average: data.app.rating_average,
          rating_count: data.app.rating_count }, release_id: running?.appId === id ? running.releaseId : null };
      } else if (operation === 'rating') {
        if (!Number.isInteger(payload.score) || payload.score < 1 || payload.score > 5) throw failure('评分无效');
        result = await request(`${path}/rating`, 'PUT', { score: payload.score }, valid);
      } else if (operation === 'tip-status') result = await request(`${path}/tip`);
      else if (operation === 'tip') {
        if (!Number.isSafeInteger(payload.amount) || payload.amount <= 0 ||
            !/^[a-f0-9-]{36}$/.test(payload.request_id)) throw failure('打赏参数无效');
        result = await request(`${path}/tip`, 'POST', { amount: payload.amount, request_id: payload.request_id }, valid);
      } else if (operation === 'flags') result = await flags();
      else if (operation === 'report') {
        if (running?.appId !== id || running.releaseId !== payload.release_id) throw failure('暂时无法确认运行版本，请重新进入小程序后重试');
        if (!Number.isInteger(payload.flag_type_id) || payload.flag_type_id < 1 || typeof payload.message !== 'string' ||
            payload.message.length > 500) throw failure('举报参数无效');
        result = await request(`${path}/report`, 'POST', { flag_type_id: payload.flag_type_id, release_id: running.releaseId,
          ...(payload.message ? { message: payload.message } : {}) }, valid);
      } else throw failure('不支持的操作');
      if (valid()) bridge.postMessage(JSON.stringify({ type: 'action-result', id: token, appId: id, url: address, data: result }));
    } catch (error) {
      if (valid()) bridge.postMessage(JSON.stringify({ type: 'action-result', id: token, appId: id, url: address,
        error: typeof error.message === 'string' ? error.message.slice(0, 500) : '操作失败，请重试', uncertain: error.uncertain === true }));
    }
  };
})();
