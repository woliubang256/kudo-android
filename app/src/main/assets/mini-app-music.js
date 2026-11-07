(() => {
  const id = '01a1a755-8210-7a3f-877b-6b09df8dce08';
  if (location.protocol !== 'https:' || location.host !== 'linux.do' || window.__kudoMusicInstalled) return;
  const appPath = `/apps/${id}`;
  const runtimePath = `/mini-app-api/production/${id}/runtime`;
  const type = 'kudo-mini-music-v1';
  const isApp = url => url.origin === 'https://linux.do' && [appPath, appPath + '/'].includes(url.pathname);
  const runtime = frame => {
    try { const url = new URL(frame.src); return url.origin === 'https://linux.do' && url.pathname === runtimePath; }
    catch (_) { return false; }
  };
  if (window.top === window && isApp(new URL(location.href))) {
    window.__kudoMusicInstalled = true;
    const host = () => document.querySelector('iframe.mini-app-frame-host');
    addEventListener('message', event => {
      if (event.source !== host()?.contentWindow || event.origin !== 'https://linux.do' ||
          event.data?.type !== type || event.data.action !== 'state' || !isApp(new URL(location.href))) return;
      const data = event.data;
      if (typeof data.playing !== 'boolean' || typeof data.canControl !== 'boolean') return;
      window.KudoMiniAppMusic?.postMessage(JSON.stringify({type, appId:id, url:location.href,
        title:String(data.title || '').slice(0,200), artist:String(data.artist || '').slice(0,200),
        playing:data.playing, canControl:data.canControl, canSkip:data.canSkip === true}));
    });
    window.__kudoMiniMusicCommand = action => {
      if (!['play','pause','previous','next'].includes(action) || !isApp(new URL(location.href))) return;
      host()?.contentWindow?.postMessage({type, action}, 'https://linux.do');
    };
  } else if (location.pathname === '/mini-app-api/frame-host') {
    try { if (!isApp(new URL(parent.location.href))) return; } catch (_) { return; }
    window.__kudoMusicInstalled = true;
    const frame = () => [...document.querySelectorAll('iframe')].find(runtime);
    addEventListener('message', event => {
      const child = frame();
      if (!child || event.data?.type !== type) return;
      if (event.source === child.contentWindow && event.data.action === 'state') parent.postMessage(event.data, 'https://linux.do');
      else if (event.source === parent && event.origin === 'https://linux.do' && ['play','pause','previous','next'].includes(event.data.action))
        child.contentWindow.postMessage({type,action:event.data.action}, '*');
    });
  } else if (window.top !== window && location.pathname === runtimePath) {
    window.__kudoMusicInstalled = true;
    let last = '';
    const report = () => {
      const toggle = document.getElementById('toggle');
      const data = {type,action:'state',title:document.getElementById('track-title')?.textContent?.trim() || '',
        artist:document.getElementById('track-artist')?.textContent?.trim() || '',
        playing:toggle?.classList.contains('is-playing') === true, canControl:!!toggle && !toggle.disabled,
        canSkip:!!document.getElementById('next') && !document.getElementById('next').disabled};
      const value = JSON.stringify(data);
      if (value !== last) { last = value; parent.postMessage(data, 'https://linux.do'); }
    };
    const command = event => {
      if (event.source !== parent || event.origin !== 'https://linux.do' || event.data?.type !== type) return;
      const action = event.data.action, toggle = document.getElementById('toggle');
      if (!toggle || toggle.disabled) return;
      if (action === 'play' || action === 'pause') {
        if (toggle.classList.contains('is-playing') !== (action === 'play')) toggle.click();
      } else if (action === 'next' || action === 'previous') {
        const button = document.getElementById(action); if (button && !button.disabled) button.click();
      }
      last = ''; setTimeout(report,100);
    };
    // The official mount calls document.open/write after bootstrap. That removes
    // window listeners and replaces the observed root; timers survive the mount.
    // Registering the same function again is idempotent and restores it afterwards.
    const sync = () => { addEventListener('message',command); report(); };
    sync(); setInterval(sync,500);
  }
})();
