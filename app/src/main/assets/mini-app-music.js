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
        album:String(data.album || '').slice(0,200), playing:data.playing, canControl:data.canControl,
        canSkip:data.canSkip === true, canPrevious:data.canPrevious === true, canSeek:data.canSeek === true,
        durationMs:data.durationMs, positionMs:data.positionMs, artworkKey:String(data.artworkKey || '').slice(0,1000),
        ...(typeof data.artwork === 'string' && data.artwork.length <= 100000 ? {artwork:data.artwork} : {})}));
    });
    window.__kudoMiniMusicCommand = (action, positionMs) => {
      if (!['play','pause','previous','next','seek'].includes(action) || !isApp(new URL(location.href))) return;
      if (action === 'seek' && (!Number.isFinite(positionMs) || positionMs < 0 || positionMs > 86400000)) return;
      host()?.contentWindow?.postMessage({type, action, positionMs}, 'https://linux.do');
    };
  } else if (location.pathname === '/mini-app-api/frame-host') {
    try { if (!isApp(new URL(parent.location.href))) return; } catch (_) { return; }
    window.__kudoMusicInstalled = true;
    const frame = () => [...document.querySelectorAll('iframe')].find(runtime);
    addEventListener('message', event => {
      const child = frame();
      if (!child || event.data?.type !== type) return;
      if (event.source === child.contentWindow && event.data.action === 'state') parent.postMessage(event.data, 'https://linux.do');
      else if (event.source === parent && event.origin === 'https://linux.do' && ['play','pause','previous','next','seek'].includes(event.data.action))
        child.contentWindow.postMessage({type,action:event.data.action,positionMs:event.data.positionMs}, '*');
    });
  } else if (window.top !== window && location.pathname === runtimePath) {
    window.__kudoMusicInstalled = true;
    let last = '', coverKey = '', coverData = '', captured = false, sentKey = '', sentCover = '';
    const text = id => document.getElementById(id)?.textContent?.trim() || '';
    const time = id => {
      const value = text(id); if (!/^\d{1,3}:\d{2}(:\d{2})?$/.test(value)) return -1;
      const parts = value.split(':').map(Number); if (parts.slice(1).some(x => x > 59)) return -1;
      return parts.reduce((total, part) => total * 60 + part, 0) * 1000;
    };
    const hash = value => { let h=2166136261; for (const c of value) h=Math.imul(h ^ c.charCodeAt(0),16777619); return String(h>>>0); };
    const report = () => {
      const toggle = document.getElementById('toggle');
      const image = document.getElementById('cover');
      const source = image?.getAttribute('src') ? image.currentSrc || image.src : '';
      const key = hash([text('track-title'),text('track-artist'),source].join('\n'));
      if (key !== coverKey) { coverKey=key; coverData=''; captured=false; }
      if (!captured && source && image.complete && image.naturalWidth > 0) {
        captured=true;
        try {
          // The player creates blob covers inside its opaque sandbox. Transfer a
          // bounded thumbnail, never try to load that blob from native code.
          const canvas=document.createElement('canvas'), scale=Math.min(1,320/image.naturalWidth,320/image.naturalHeight);
          canvas.width=Math.max(1,Math.round(image.naturalWidth*scale)); canvas.height=Math.max(1,Math.round(image.naturalHeight*scale));
          canvas.getContext('2d').drawImage(image,0,0,canvas.width,canvas.height);
          const value=canvas.toDataURL('image/jpeg',.75); if(value.length<=100000)coverData=value;
        } catch (_) {}
      }
      const duration = time('duration'), position = time('elapsed'), seek = document.getElementById('seek');
      const data = {type,action:'state',title:text('track-title').slice(0,200), artist:text('track-artist').slice(0,200),
        album:text('track-album').slice(0,200), artworkKey:key, durationMs:duration,
        positionMs:position >= 0 && duration > 0 ? Math.min(position,duration) : -1,
        canSeek:!!seek && !seek.disabled && duration > 0 && Number(seek.max)>Number(seek.min),
        playing:toggle?.classList.contains('is-playing') === true, canControl:!!toggle && !toggle.disabled,
        canSkip:!!document.getElementById('next') && !document.getElementById('next').disabled,
        canPrevious:!!document.getElementById('previous') && !document.getElementById('previous').disabled};
      const value = JSON.stringify({...data,artwork:coverData});
      if (value !== last) {
        last=value;
        if(sentKey!==key || sentCover!==coverData) { data.artwork=coverData; sentKey=key; sentCover=coverData; }
        parent.postMessage(data, 'https://linux.do');
      }
    };
    const command = event => {
      if (event.source !== parent || event.origin !== 'https://linux.do' || event.data?.type !== type) return;
      const action = event.data.action, toggle = document.getElementById('toggle');
      if (!toggle || toggle.disabled) return;
      if (action === 'play' || action === 'pause') {
        if (toggle.classList.contains('is-playing') !== (action === 'play')) toggle.click();
      } else if (action === 'next' || action === 'previous') {
        const button = document.getElementById(action); if (button && !button.disabled) button.click();
      } else if (action === 'seek') {
        const seek=document.getElementById('seek'), duration=time('duration'), position=event.data.positionMs;
        if(!seek || seek.disabled || duration<=0 || !Number.isFinite(position) || position<0 || position>duration) return;
        const min=Number(seek.min), max=Number(seek.max); if(!Number.isFinite(min) || !Number.isFinite(max) || max<=min) return;
        seek.value=String(min+position/duration*(max-min)); seek.dispatchEvent(new Event('change',{bubbles:true}));
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
