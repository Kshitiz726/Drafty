// Drafty reply panel: chat boxes, email composers and comment boxes on any site.
// Drafts are never sent. Insert (or auto-insert) only places text in the box for you to review.

(() => {
  if (window.__draftyLoaded) return;
  window.__draftyLoaded = true;

  const A = window.DraftyAdapters, P = window.DraftyPrompts, UI = window.DraftyUI;
  const settings = UI.settings;
  const chat = A.chatAdapter();
  const PROVIDER_NAME = { claude: 'Claude', chatgpt: 'ChatGPT', gemini: 'Gemini', grok: 'Grok' };

  const CSS = `
    .fab {
      position: fixed; display: none; align-items: center; justify-content: center; gap: 6px;
      height: 28px; padding: 0 11px 0 9px; border-radius: 999px; border: 0; cursor: pointer;
      background: var(--accent); color: #fff; font-size: 12px; font-weight: 600;
      box-shadow: 0 4px 14px rgba(217,119,87,.35); transition: transform .15s, box-shadow .15s;
    }
    .fab:hover { transform: translateY(-1px); box-shadow: 0 6px 18px rgba(217,119,87,.45); }
    .fab svg { width: 13px; height: 13px; }
    .fab.ready::after { content: ''; position: absolute; top: -2px; right: -2px; width: 9px; height: 9px; border-radius: 50%; background: #34c759; border: 2px solid #fff; }
    .panel {
      position: fixed; display: flex; flex-direction: column; gap: 10px; padding: 12px;
      background: var(--bg); border: 1px solid var(--line); border-radius: 16px; box-shadow: var(--shadow);
      font-size: 13px; animation: pop .16s cubic-bezier(.2,.8,.2,1);
    }
    .panel[hidden] { display: none; }
    @keyframes pop { from { opacity: 0; transform: translateY(6px) scale(.98); } to { opacity: 1; transform: none; } }
    .hd { display: flex; align-items: center; gap: 8px; }
    .logo { width: 22px; height: 22px; border-radius: 7px; background: var(--accent); color: #fff; display: grid; place-items: center; flex: none; }
    .logo svg { width: 13px; height: 13px; }
    .ttl { font-weight: 650; }
    .via { color: var(--muted); font-size: 11px; }
    .stats { color: var(--muted); font-size: 11px; flex: 1; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; text-align: right; }
    .icon { background: none; border: 0; color: var(--muted); cursor: pointer; padding: 4px; border-radius: 8px; display: grid; place-items: center; }
    .icon:hover { color: var(--fg); background: var(--bg2); }
    .tabs { display: flex; gap: 2px; background: var(--bg2); padding: 3px; border-radius: 11px; overflow-x: auto; scrollbar-width: none; }
    .tabs button { flex: 1; white-space: nowrap; border: 0; background: none; color: var(--muted); padding: 5px 10px; border-radius: 8px; cursor: pointer; font-size: 12px; font-weight: 600; transition: background .15s, color .15s; }
    .tabs button.on { background: var(--bg); color: var(--fg); box-shadow: 0 1px 3px rgba(0,0,0,.1); }
    textarea.draft {
      width: 100%; resize: none; border: 1px solid var(--line); border-radius: 12px; padding: 10px 12px;
      background: var(--field); color: var(--fg); font-size: 14px; line-height: 1.5; min-height: 48px; max-height: 300px; outline: none;
    }
    textarea.draft:focus { border-color: var(--accent); }
    textarea.draft.streaming, .result.streaming { background-image: linear-gradient(90deg, transparent, var(--accent-soft), transparent); background-size: 200% 100%; animation: shimmer 1.4s linear infinite; }
    @keyframes shimmer { from { background-position: 200% 0; } to { background-position: -200% 0; } }
    .result { max-height: 340px; overflow-y: auto; padding: 10px 12px; border: 1px solid var(--line); border-radius: 12px; background: var(--field); min-height: 48px; }
    .ideas { display: flex; flex-direction: column; gap: 6px; }
    .idea { text-align: left; border: 1px solid var(--line); background: var(--field); color: var(--fg); border-radius: 12px; padding: 9px 12px; cursor: pointer; font-size: 13.5px; line-height: 1.45; }
    .idea:hover { border-color: var(--accent); background: var(--accent-soft); }
    .status { color: var(--muted); font-size: 12px; }
    .status:empty { display: none; }
    .status.err { color: var(--danger); }
    .status a, .status button.link { color: var(--accent); background: none; border: 0; padding: 0; cursor: pointer; font: inherit; text-decoration: underline; }
    .chips { display: flex; flex-wrap: wrap; gap: 6px; }
    .chips button { background: var(--bg2); color: var(--fg); border: 1px solid var(--line); border-radius: 999px; padding: 4px 11px; font-size: 12px; cursor: pointer; }
    .chips button:hover { border-color: var(--accent); background: var(--accent-soft); }
    input.ask { width: 100%; border: 1px solid var(--line); border-radius: 10px; padding: 8px 10px; font-size: 12.5px; background: var(--field); color: var(--fg); outline: none; }
    input.ask:focus { border-color: var(--accent); }
    .ft { display: flex; align-items: center; gap: 6px; }
    .btn { display: inline-flex; align-items: center; gap: 6px; border: 1px solid var(--line); background: var(--bg2); color: var(--fg); border-radius: 10px; padding: 7px 12px; font-size: 12.5px; cursor: pointer; }
    .btn:hover { filter: brightness(.97); }
    .btn.primary { background: var(--accent); border-color: var(--accent); color: #fff; font-weight: 600; }
    .btn:disabled { opacity: .5; cursor: default; }
    kbd { font: 10px inherit; opacity: .75; }
    .hint { margin-left: auto; color: var(--muted); font-size: 11px; }
    [hidden] { display: none !important; }
  `;

  const { root } = UI.makeHost('drafty-root', CSS, `
    <div class="theme" id="t">
      <button class="fab" title="Draft a reply (Alt+R)">${UI.ICON.spark}<span>Reply</span></button>
      <div class="panel" hidden>
        <div class="hd">
          <span class="logo">${UI.ICON.spark}</span><span class="ttl">Drafty</span><span class="via"></span>
          <span class="stats"></span><button class="icon x" title="Close (Esc)">${UI.ICON.close}</button>
        </div>
        <div class="tabs"></div>
        <textarea class="draft" rows="2" spellcheck="true" placeholder="Drafting..."></textarea>
        <div class="result md" hidden></div>
        <div class="ideas" hidden></div>
        <div class="status"></div>
        <div class="chips reply-only">
          <button data-mod="shorter">Shorter</button>
          <button data-mod="longer">Longer</button>
          <button data-mod="warmer">Warmer</button>
          <button data-mod="formal">More formal</button>
          <button data-mod="funny">Funnier</button>
          <button data-mod="no">Say no nicely</button>
          <button data-mod="translate">Translate</button>
        </div>
        <input class="ask reply-only" placeholder="What should change? Select part of the draft to edit only that part" />
        <div class="ft">
          <button class="btn primary ins reply-only" title="Place the draft in the text box. Does not send.">Insert <kbd>Ctrl+Enter</kbd></button>
          <button class="btn cp">${UI.ICON.copy}<span>Copy</span></button>
          <button class="btn rg" title="Regenerate">${UI.ICON.refresh}</button>
          <span class="hint">Never sent automatically</span>
        </div>
      </div>
    </div>`);

  const $ = s => root.querySelector(s);
  const themeEl = $('#t'), fab = $('.fab'), panel = $('.panel'), ta = $('.draft'), resultEl = $('.result'), ideasEl = $('.ideas');
  const statusEl = $('.status'), statsEl = $('.stats'), viaEl = $('.via'), tabsEl = $('.tabs'), askEl = $('.ask');
  const insBtn = $('.ins'), cpBtn = $('.cp'), rgBtn = $('.rg');

  // ---------- state ----------
  let composer = null, focused = null, blurAt = 0;
  let job = null, streaming = false;
  let tab = 'reply', mode = null, ctx = null;
  let currentSig = null, chatKey = '', lastResult = '';
  const cache = new Map();
  const handled = new Set();
  let pendingSig = null;

  const isOpen = () => !panel.hidden;

  document.addEventListener('focusin', e => {
    const el = A.editableRoot(e.composedPath()[0]);
    if (el && A.isComposerLike(el)) { focused = el; blurAt = 0; position(); }
  }, true);
  document.addEventListener('focusout', e => {
    const el = A.editableRoot(e.composedPath()[0]);
    if (el && el === focused) { blurAt = Date.now(); setTimeout(position, 2600); }
  }, true);

  function currentBox() {
    if (chat && chat.enabled()) { const c = chat.composer(); if (c) return c; }
    if (focused?.isConnected && A.visible(focused) && (!blurAt || Date.now() - blurAt < 2500 || isOpen())) return focused;
    return null;
  }

  function position() {
    if (!isOpen()) composer = currentBox();
    const r = composer?.isConnected ? composer.getBoundingClientRect() : null;
    if (r && r.width && !isOpen()) {
      fab.style.display = 'inline-flex';
      fab.style.top = (r.top > 40 ? r.top - 34 : r.bottom + 6) + 'px';
      fab.style.left = Math.max(8, Math.min(innerWidth - 90, r.right - 84)) + 'px';
    } else fab.style.display = 'none';
    if (isOpen()) {
      const width = Math.min(innerWidth - 16, Math.max(380, Math.min(560, r ? r.width : 500)));
      panel.style.width = width + 'px';
      if (r && r.width) {
        panel.style.left = Math.min(Math.max(8, r.right - width), innerWidth - width - 8) + 'px';
        if (r.top > 340) { panel.style.bottom = (innerHeight - r.top + 10) + 'px'; panel.style.top = 'auto'; }
        else { panel.style.top = Math.min(r.bottom + 10, innerHeight - 380) + 'px'; panel.style.bottom = 'auto'; }
      } else {
        panel.style.left = (innerWidth - width - 16) + 'px';
        panel.style.bottom = '16px'; panel.style.top = 'auto';
      }
    }
  }

  const autosize = () => { ta.style.height = 'auto'; ta.style.height = Math.min(300, ta.scrollHeight + 2) + 'px'; };

  function setStatus(html, isErr = false) {
    statusEl.innerHTML = html;
    statusEl.classList.toggle('err', isErr);
  }

  function setStreaming(on) {
    streaming = on;
    ta.readOnly = on;
    ta.classList.toggle('streaming', on && tab === 'reply');
    resultEl.classList.toggle('streaming', on && tab !== 'reply');
    rgBtn.innerHTML = on ? UI.ICON.stop : UI.ICON.refresh;
    rgBtn.title = on ? 'Stop' : 'Regenerate';
    insBtn.disabled = on;
  }

  function stop() { job?.stop(); job = null; setStreaming(false); }
  function close() { stop(); panel.hidden = true; position(); }

  // ---------- tabs ----------
  const TABS = {
    chat: [['reply', 'Reply'], ['catchup', 'Catch up'], ['analyze', 'Analyze'], ['translate', 'Translate'], ['ideas', 'Ideas']],
    email: [['reply', 'Reply'], ['catchup', 'Catch up'], ['summary', 'Summarize'], ['translate', 'Translate'], ['ideas', 'Ideas']],
    comment: [['reply', 'Reply'], ['summary', 'Summarize'], ['translate', 'Translate'], ['ideas', 'Ideas']],
  };

  function renderTabs() {
    tabsEl.innerHTML = (TABS[mode?.kind] || TABS.comment)
      .map(([k, label]) => `<button data-tab="${k}" class="${k === tab ? 'on' : ''}">${label}</button>`).join('');
  }

  function showView() {
    const reply = tab === 'reply';
    ta.hidden = !reply;
    resultEl.hidden = reply || tab === 'ideas';
    ideasEl.hidden = tab !== 'ideas';
    root.querySelectorAll('.reply-only').forEach(e => { e.hidden = !reply; });
    tabsEl.querySelectorAll('button').forEach(b => b.classList.toggle('on', b.dataset.tab === tab));
  }

  tabsEl.addEventListener('click', e => {
    const b = e.target.closest('button[data-tab]');
    if (!b || b.dataset.tab === tab) return;
    stop();
    tab = b.dataset.tab;
    showView();
    if (tab === 'reply') { if (!ta.value) generate(); else setStatus(''); }
    else runTask(tab);
  });

  // ---------- context ----------
  function readContext(full = false) {
    composer = currentBox() || A.editableRoot(A.deepActive()) || composer;
    if (!composer) return { error: 'Click into a message, email or comment box first, then press Reply or Alt+R.' };
    mode = A.forComposer(composer);
    try { return mode.context(settings, full); } catch (e) { console.warn('[Drafty]', e); return { error: 'Could not read this page.' }; }
  }

  function openPanel() {
    themeEl.dataset.theme = UI.themeFor(composer || document.body);
    viaEl.textContent = 'via ' + (PROVIDER_NAME[settings.provider] || 'Claude');
    panel.hidden = false;
    position();
  }

  function fail(message) {
    ta.value = ''; resultEl.innerHTML = ''; ideasEl.innerHTML = ''; statsEl.textContent = '';
    setStatus(UI.esc(message), true);
  }

  // ---------- reply ----------
  function generate({ instruction = '', previous = '', selection = '', auto = false } = {}) {
    stop();
    tab = 'reply';
    ctx = readContext();
    renderTabs(); showView(); openPanel();
    if (ctx.error) return fail(ctx.error);

    const sig = ctx.sig;
    currentSig = sig;
    statsEl.textContent = ctx.statsText || '';
    statsEl.title = ctx.statsText || '';
    if (!instruction && !previous && cache.has(sig)) {
      ta.value = cache.get(sig);
      autosize(); setStatus('');
      if (!auto) ta.focus();
      return;
    }

    const prompt = P.reply(ctx, { instruction, previous, selection }, settings);
    ta.value = '';
    setStreaming(true);
    setStatus(`Writing with ${PROVIDER_NAME[settings.provider] || 'Claude'}...`);
    autosize();
    job = UI.stream(prompt, {
      onText: t => { ta.value = P.clean(t); autosize(); },
      onDone: t => {
        job = null;
        const final = P.clean(t);
        ta.value = final; autosize(); setStreaming(false);
        if (!final) return setStatus('Got an empty draft. Press regenerate to retry.', true);
        cache.set(sig, final);
        setStatus('');
        if (settings.autoInsert) insert({ keepOpen: true });
        else if (!auto) { ta.focus(); ta.setSelectionRange(final.length, final.length); }
      },
      onError: m => { job = null; setStreaming(false); setStatus(UI.errorHtml(m), true); },
    });
  }

  // ---------- panel actions: catch up, analyze, summarize, translate, ideas ----------
  function runTask(task) {
    stop();
    const def = P.PANEL[task];
    ctx = readContext(!!def.full);
    if (ctx.error) return fail(ctx.error);
    statsEl.textContent = ctx.statsText || '';
    const prompt = P.panel(task, ctx, settings);
    resultEl.innerHTML = ''; ideasEl.innerHTML = '';
    lastResult = '';
    setStreaming(true);
    setStatus(`${def.label}...`);
    job = UI.stream(prompt, {
      onText: t => {
        lastResult = P.clean(t);
        if (task === 'ideas') renderIdeas(lastResult);
        else resultEl.innerHTML = UI.md(lastResult);
      },
      onDone: t => {
        job = null; setStreaming(false); setStatus('');
        lastResult = P.clean(t);
        if (task === 'ideas') renderIdeas(lastResult, true); else resultEl.innerHTML = UI.md(lastResult);
      },
      onError: m => { job = null; setStreaming(false); setStatus(UI.errorHtml(m), true); },
    });
  }

  function renderIdeas(text, final = false) {
    const items = text.split(/\n(?=\s*\d+[.)]\s)/).map(s => s.replace(/^\s*\d+[.)]\s*/, '').trim()).filter(Boolean);
    ideasEl.innerHTML = items.map((t, i) => `<button class="idea" data-i="${i}">${UI.esc(t)}</button>`).join('') ||
      (final ? '<div class="status">No ideas came back. Try regenerate.</div>' : '');
    ideasEl._items = items;
  }

  ideasEl.addEventListener('click', e => {
    const b = e.target.closest('.idea');
    if (!b) return;
    const text = ideasEl._items[+b.dataset.i];
    tab = 'reply'; showView();
    ta.value = text; autosize();
    if (currentSig) cache.set(currentSig, text);
    if (settings.autoInsert) insert({ keepOpen: true }); else ta.focus();
  });

  resultEl.addEventListener('click', e => {
    const c = e.target.closest('[data-cid]');
    if (c) { e.preventDefault(); window.DraftyPage.jumpTo(c.dataset.cid); }
  });

  // ---------- insert (never sends) ----------
  const norm = s => (s || '').replace(/\s+/g, ' ').trim();

  async function insert({ keepOpen = false } = {}) {
    const text = ta.value.trim();
    if (!text) return;
    const target = composer?.isConnected ? composer : currentBox();
    if (!target) {
      await navigator.clipboard.writeText(text).catch(() => {});
      return setStatus('No text box found, so the draft was copied. Paste it with Ctrl+V.');
    }
    const ok = await putText(target, text);
    if (!ok) {
      await navigator.clipboard.writeText(text).catch(() => {});
      return setStatus('This site blocked direct insert, so the draft was copied. Click the box and press Ctrl+V.');
    }
    if (currentSig) cache.set(currentSig, text);
    if (keepOpen) setStatus('Inserted into the box. Edit it there, or tell me what to change below.');
    else { close(); target.focus(); }
  }

  function selectAllIn(el) {
    el.focus();
    const sel = (el.getRootNode?.().getSelection?.() || getSelection());
    const range = document.createRange();
    range.selectNodeContents(el);
    sel.removeAllRanges();
    sel.addRange(range);
  }

  const frame = () => new Promise(r => requestAnimationFrame(() => setTimeout(r, 40)));

  async function putText(el, text) {
    if (el.tagName === 'TEXTAREA') {
      el.focus();
      Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set.call(el, text);
      el.dispatchEvent(new Event('input', { bubbles: true }));
      el.dispatchEvent(new Event('change', { bubbles: true }));
      return true;
    }
    const probe = norm(text).slice(0, 24);
    const has = () => norm(el.innerText).includes(probe);
    selectAllIn(el);
    const dt = new DataTransfer();
    dt.setData('text/plain', text);
    el.dispatchEvent(new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true, composed: true }));
    await frame();
    if (has()) return true;
    selectAllIn(el);
    document.execCommand('insertText', false, text);
    await frame();
    return has();
  }

  // ---------- events ----------
  fab.addEventListener('mousedown', e => e.preventDefault());
  fab.addEventListener('click', () => { fab.classList.remove('ready'); generate(); });
  $('.x').addEventListener('click', close);
  insBtn.addEventListener('click', () => insert());
  cpBtn.addEventListener('click', async () => {
    await navigator.clipboard.writeText(tab === 'reply' ? ta.value : lastResult).catch(() => {});
    cpBtn.querySelector('span').textContent = 'Copied';
    setTimeout(() => (cpBtn.querySelector('span').textContent = 'Copy'), 1200);
  });
  rgBtn.addEventListener('click', () => {
    if (streaming) { stop(); setStatus('Stopped.'); return; }
    if (tab !== 'reply') return runTask(tab);
    if (currentSig) cache.delete(currentSig);
    generate({ instruction: askEl.value.trim() });
  });
  root.querySelectorAll('.chips button').forEach(b => b.addEventListener('click', () => {
    const mod = b.dataset.mod === 'translate'
      ? `Translate it into ${settings.translateTo || 'English'}, keeping the same tone.`
      : P.MODS[b.dataset.mod];
    generate({ instruction: mod, previous: ta.value.trim() });
  }));
  askEl.addEventListener('keydown', e => {
    if (e.key === 'Enter' && askEl.value.trim()) {
      e.preventDefault();
      const s = ta.selectionStart, en = ta.selectionEnd;
      const selection = en > s ? ta.value.slice(s, en) : '';
      generate({ instruction: askEl.value.trim(), previous: ta.value.trim(), selection });
      askEl.value = '';
    }
    if (e.key === 'Escape') close();
  });
  statusEl.addEventListener('click', e => {
    if (e.target.closest('.redraft')) { if (currentSig) cache.delete(currentSig); tab === 'reply' ? generate() : runTask(tab); }
  });
  ta.addEventListener('input', autosize);
  ta.addEventListener('keydown', e => {
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); insert(); }
    if (e.key === 'Escape') close();
  });

  try {
    chrome.runtime.onMessage.addListener((msg, _s, respond) => {
      if (msg.type === 'ping') { respond({ ok: true }); return; }
      if (msg.type === 'toggle') {
        if (isOpen()) close(); else { fab.classList.remove('ready'); generate(); }
        respond({ ok: true });
      }
    });
  } catch {}

  addEventListener('resize', position);
  addEventListener('scroll', position, true);

  // ---------- loop: follow the box, notice new messages, auto-draft ----------
  let tick = 0;
  setInterval(() => {
    tick++;
    if (chat) {
      const key = location.pathname + '|' + (chat.enabled() && chat.composer() ? chat.title() : '');
      if (key !== chatKey) { chatKey = key; fab.classList.remove('ready'); if (isOpen()) close(); }
    }
    position();

    // New messages while the panel is open: offer a redraft with the fresh context.
    if (isOpen() && !streaming && mode?.kind === 'chat' && tick % 3 === 0 && composer) {
      try {
        const fresh = mode.context(settings);
        if (fresh.sig && currentSig && fresh.sig !== currentSig && !statusEl.querySelector('.redraft')) {
          setStatus('New messages arrived. <button class="link redraft">Redraft with latest</button>');
        }
      } catch {}
    }

    if (!chat || !chat.enabled() || tick % 2 || !settings.auto || isOpen() || streaming) return;
    const box = chat.composer();
    if (!box || document.visibilityState !== 'visible' || !document.hasFocus()) return;
    if (norm(box.innerText ?? box.value)) return;
    let msgs;
    try { msgs = chat.messages(settings, box); } catch { return; }
    if (!msgs?.length || msgs[msgs.length - 1].who !== 'them') return;
    const sig = msgs.slice(-3).map(m => m.who + m.text).join('|');
    if (handled.has(sig)) return;
    if (pendingSig !== sig) { pendingSig = sig; return; }
    handled.add(sig);
    generate({ auto: true });
  }, 700);
})();
