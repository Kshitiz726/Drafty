// Drafty page assistant: summarize posts and comments, find comments by meaning, ask anything.

(() => {
  if (window.__draftyAssistant) return;
  window.__draftyAssistant = true;

  const UI = window.DraftyUI, P = window.DraftyPrompts, PG = window.DraftyPage, A = window.DraftyAdapters;
  const settings = UI.settings;
  const H = location.hostname;
  const NO_LAUNCHER = /(^|\.)(web\.whatsapp\.com|discord\.com|web\.telegram\.org|messenger\.com|mail\.google\.com|outlook\.(live|office|office365)\.com|docs\.google\.com)$/;

  const CSS = `
    .launcher {
      position: fixed; right: 20px; bottom: 20px; display: none; align-items: center; gap: 8px;
      height: 38px; padding: 0 8px 0 14px; border-radius: 999px; background: var(--bg); color: var(--fg);
      border: 1px solid var(--line); box-shadow: var(--shadow); cursor: pointer; font-size: 13px; font-weight: 600;
      animation: rise .25s cubic-bezier(.2,.8,.2,1);
    }
    .launcher .dot { width: 22px; height: 22px; border-radius: 7px; background: var(--accent); color: #fff; display: grid; place-items: center; }
    .launcher .dot svg { width: 13px; height: 13px; }
    .launcher .dismiss { background: none; border: 0; color: var(--muted); border-radius: 50%; width: 24px; height: 24px; display: grid; place-items: center; cursor: pointer; }
    .launcher .dismiss:hover { background: var(--bg2); color: var(--fg); }
    .launcher:hover { border-color: var(--accent); }
    @keyframes rise { from { opacity: 0; transform: translateY(10px); } to { opacity: 1; transform: none; } }

    .drawer {
      position: fixed; top: 12px; right: 12px; bottom: 12px; width: 410px; max-width: calc(100vw - 24px);
      display: flex; flex-direction: column; background: var(--bg); border: 1px solid var(--line);
      border-radius: 18px; box-shadow: var(--shadow); overflow: hidden; font-size: 13px;
      animation: slide .22s cubic-bezier(.2,.8,.2,1);
    }
    .drawer[hidden] { display: none; }
    @keyframes slide { from { opacity: 0; transform: translateX(24px); } to { opacity: 1; transform: none; } }
    .top { display: flex; align-items: center; gap: 10px; padding: 14px 14px 10px; }
    .brand { width: 30px; height: 30px; border-radius: 9px; background: var(--accent); color: #fff; display: grid; place-items: center; flex: none; }
    .titles { flex: 1; min-width: 0; }
    .titles b { display: block; font-size: 14px; }
    .titles span { display: block; color: var(--muted); font-size: 11.5px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .icon { background: none; border: 0; color: var(--muted); cursor: pointer; width: 30px; height: 30px; border-radius: 9px; display: grid; place-items: center; }
    .icon:hover { color: var(--fg); background: var(--bg2); }

    .grid { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 8px; padding: 0 14px 12px; }
    .tile {
      display: flex; flex-direction: column; align-items: flex-start; gap: 8px; padding: 10px; text-align: left;
      background: var(--bg2); border: 1px solid transparent; border-radius: 12px; cursor: pointer; color: var(--fg);
      font-size: 12px; font-weight: 600; line-height: 1.25; transition: border-color .15s, background .15s;
    }
    .tile:hover { border-color: var(--accent); background: var(--accent-soft); }
    .tile svg { color: var(--accent); }
    .tile.active { border-color: var(--accent); }

    .body { flex: 1; overflow-y: auto; padding: 4px 14px 14px; border-top: 1px solid var(--line); }
    .empty { color: var(--muted); text-align: center; padding: 36px 16px; line-height: 1.6; }
    .empty b { color: var(--fg); display: block; font-size: 14px; margin-bottom: 4px; }
    .card { padding-top: 12px; }
    .card-hd { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
    .card-hd .label { font-weight: 650; font-size: 13px; flex: 1; }
    .card-hd .meta { color: var(--muted); font-size: 11px; }
    .quote { color: var(--muted); border-left: 3px solid var(--line); padding: 2px 0 2px 10px; margin-bottom: 10px; font-size: 12.5px; max-height: 80px; overflow: hidden; }
    .err { color: var(--danger); }
    .err a { color: var(--accent); }
    .actions { display: flex; gap: 6px; margin-top: 12px; }
    .btn {
      display: inline-flex; align-items: center; gap: 6px; border: 1px solid var(--line); background: var(--bg2); color: var(--fg);
      border-radius: 9px; padding: 6px 11px; font-size: 12px; cursor: pointer;
    }
    .btn:hover { border-color: var(--accent); }
    .typing { display: inline-flex; gap: 4px; padding: 6px 0; }
    .typing i { width: 6px; height: 6px; border-radius: 50%; background: var(--accent); opacity: .4; animation: blink 1s infinite; }
    .typing i:nth-child(2) { animation-delay: .15s; } .typing i:nth-child(3) { animation-delay: .3s; }
    @keyframes blink { 50% { opacity: 1; } }

    .hit { display: block; width: 100%; text-align: left; background: var(--bg2); border: 1px solid transparent; border-radius: 12px; padding: 10px 12px; margin: 8px 0; cursor: pointer; color: var(--fg); }
    .hit:hover { border-color: var(--accent); }
    .hit .who { font-size: 11.5px; color: var(--muted); display: flex; gap: 6px; align-items: center; margin-bottom: 4px; }
    .hit .who .id { background: var(--accent-soft); color: var(--accent); font-weight: 650; border-radius: 6px; padding: 0 6px; }
    .hit .txt { font-size: 13px; line-height: 1.45; display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden; }
    .hit .why { font-size: 11.5px; color: var(--accent); margin-top: 6px; }

    .foot { border-top: 1px solid var(--line); padding: 10px 12px 12px; background: var(--bg); }
    .seg { display: inline-flex; background: var(--bg2); border-radius: 9px; padding: 2px; margin-bottom: 8px; }
    .seg button { border: 0; background: none; color: var(--muted); padding: 4px 12px; border-radius: 7px; cursor: pointer; font-size: 12px; font-weight: 600; display: inline-flex; gap: 5px; align-items: center; }
    .seg button.on { background: var(--bg); color: var(--fg); box-shadow: 0 1px 3px rgba(0,0,0,.12); }
    .inrow { display: flex; gap: 8px; align-items: flex-end; }
    .inrow textarea {
      flex: 1; resize: none; border: 1px solid var(--line); border-radius: 12px; padding: 9px 11px; min-height: 40px; max-height: 120px;
      background: var(--field); color: var(--fg); font-size: 13px; line-height: 1.4; outline: none;
    }
    .inrow textarea:focus { border-color: var(--accent); }
    .go { width: 40px; height: 40px; border-radius: 12px; border: 0; background: var(--accent); color: #fff; cursor: pointer; display: grid; place-items: center; flex: none; }
    .go:disabled { opacity: .5; cursor: default; }
  `;

  const TILES = [
    ['tldr', 'doc', 'TL;DR'],
    ['comments', 'comments', 'Summarize comments'],
    ['opinions', 'scale', 'Opinions and sentiment'],
    ['highlights', 'star', 'Best comments'],
    ['write', 'pen', 'Write a comment'],
    ['explain', 'bulb', 'Explain simply'],
  ];

  const { root } = UI.makeHost('drafty-assistant', CSS, `
    <div class="theme" id="t">
      <div class="launcher" role="button" tabindex="0">
        <span class="dot">${UI.ICON.spark}</span><span class="ltxt">Summarize</span>
        <button class="dismiss" title="Hide on this page">${UI.ICON.close}</button>
      </div>
      <aside class="drawer" hidden>
        <div class="top">
          <span class="brand">${UI.ICON.spark}</span>
          <div class="titles"><b>Drafty</b><span class="sub"></span></div>
          <button class="icon reread" title="Re-read page">${UI.ICON.refresh}</button>
          <button class="icon close" title="Close (Esc)">${UI.ICON.close}</button>
        </div>
        <div class="grid">
          ${TILES.map(([k, ic, label]) => `<button class="tile" data-task="${k}">${UI.ICON[ic]}<span>${label}</span></button>`).join('')}
        </div>
        <div class="body"><div class="empty"><b>Your page assistant</b>Pick an action above, ask a question, or switch to Find to search comments by meaning.</div></div>
        <div class="foot">
          <div class="seg"><button data-mode="ask" class="on">${UI.ICON.chat}Ask</button><button data-mode="find">${UI.ICON.search}Find comments</button></div>
          <div class="inrow">
            <textarea class="q" rows="1" placeholder="Ask anything about this page"></textarea>
            <button class="go" title="Send">${UI.ICON.send}</button>
          </div>
        </div>
      </aside>
    </div>`);

  const $ = s => root.querySelector(s);
  const themeEl = $('#t'), launcher = $('.launcher'), drawer = $('.drawer'), body = $('.body'), sub = $('.sub');
  const q = $('.q'), go = $('.go');

  let page = null;
  let job = null;
  let mode = 'ask';
  const history = [];

  const open = () => !drawer.hidden;

  function snapshot() {
    page = PG.extract();
    const n = page.comments.length;
    sub.textContent = `${page.site} · ${n ? `${n} comment${n === 1 ? '' : 's'} loaded` : 'page content'}`;
    return page;
  }

  function show() {
    themeEl.dataset.theme = UI.themeFor(document.body);
    drawer.hidden = false;
    launcher.style.display = 'none';
    snapshot();
  }

  function hide() {
    job?.stop();
    job = null;
    drawer.hidden = true;
    root.querySelectorAll('.tile.active').forEach(t => t.classList.remove('active'));
    checkLauncher(true);
  }

  // ---------- result rendering ----------
  function card(label, meta, quote) {
    body.innerHTML = `
      <div class="card">
        <div class="card-hd"><span class="label">${UI.esc(label)}</span><span class="meta">${UI.esc(meta || '')}</span></div>
        ${quote ? `<div class="quote">${UI.esc(quote)}</div>` : ''}
        <div class="out md"><span class="typing"><i></i><i></i><i></i></span></div>
        <div class="actions" hidden>
          <button class="btn copy">${UI.ICON.copy}<span>Copy</span></button>
          <button class="btn again">${UI.ICON.refresh}<span>Regenerate</span></button>
        </div>
      </div>`;
    return { out: body.querySelector('.out'), actions: body.querySelector('.actions') };
  }

  function run({ label, meta, quote, prompt, onFinish, regenerate, raw = false }) {
    job?.stop();
    const { out, actions } = card(label, meta, quote);
    let text = '', pending = false;
    const render = () => { pending = false; out.innerHTML = raw ? '' : UI.md(text); if (raw) out.textContent = text; };
    job = UI.stream(prompt, {
      onText: t => { text = t; if (!pending) { pending = true; requestAnimationFrame(render); } },
      onDone: () => {
        job = null;
        render();
        actions.hidden = false;
        actions.querySelector('.copy').onclick = async () => {
          await navigator.clipboard.writeText(text.replace(/\u2014/g, ', ')).catch(() => {});
          actions.querySelector('.copy span').textContent = 'Copied';
          setTimeout(() => (actions.querySelector('.copy span').textContent = 'Copy'), 1200);
        };
        actions.querySelector('.again').onclick = regenerate;
        onFinish?.(text);
      },
      onError: m => { job = null; out.innerHTML = `<div class="err">${UI.errorHtml(m)}</div>`; },
    });
  }

  function runTask(task) {
    snapshot();
    root.querySelectorAll('.tile').forEach(t => t.classList.toggle('active', t.dataset.task === task));
    if (['comments', 'opinions', 'highlights'].includes(task) && page.comments.length === 0) {
      body.innerHTML = `<div class="empty"><b>No comments found yet</b>Scroll down or expand the comments so they load, then try again.</div>`;
      return;
    }
    const { prompt, block } = P.assistant(task, page, settings);
    run({
      label: P.TASKS[task].label,
      meta: block.total ? `${block.used}/${block.total} comments · ~${block.tokens} tok` : `~${block.tokens} tok`,
      prompt,
      regenerate: () => runTask(task),
    });
  }

  function runAsk(question) {
    snapshot();
    root.querySelectorAll('.tile.active').forEach(t => t.classList.remove('active'));
    const { prompt, block } = P.ask(question, history, page, settings);
    run({
      label: 'Answer',
      meta: block.total ? `${block.used}/${block.total} comments` : '',
      quote: question,
      prompt,
      regenerate: () => runAsk(question),
      onFinish: text => { history.push({ q: question, a: text }); if (history.length > 6) history.shift(); },
    });
  }

  function runFind(query) {
    snapshot();
    root.querySelectorAll('.tile.active').forEach(t => t.classList.remove('active'));
    if (!page.comments.length) {
      body.innerHTML = `<div class="empty"><b>No comments found yet</b>Scroll down or expand the comments so they load, then try again.</div>`;
      return;
    }
    job?.stop();
    const { prompt, block } = P.find(query, page, settings);
    body.innerHTML = `
      <div class="card">
        <div class="card-hd"><span class="label">Find comments</span><span class="meta">searching ${block.used} of ${block.total}</span></div>
        <div class="quote">${UI.esc(query)}</div>
        <div class="hits"><span class="typing"><i></i><i></i><i></i></span></div>
      </div>`;
    const hitsEl = body.querySelector('.hits');
    let text = '';
    job = UI.stream(prompt, {
      onText: t => { text = t; },
      onDone: () => {
        job = null;
        let hits = [];
        try { hits = JSON.parse((text.match(/\[[\s\S]*\]/) || ['[]'])[0]); } catch {}
        const byId = new Map(page.comments.map(c => [c.id, c]));
        hits = hits.filter(h => byId.has(h.id));
        body.querySelector('.meta').textContent = `${hits.length} match${hits.length === 1 ? '' : 'es'} in ${block.used} comments`;
        if (!hits.length) { hitsEl.innerHTML = '<div class="empty" style="padding:20px 8px">No matching comments. Try rephrasing, or load more comments.</div>'; return; }
        hitsEl.innerHTML = hits.map(h => {
          const c = byId.get(h.id);
          return `<button class="hit" data-cid="${h.id}">
            <div class="who"><span class="id">${h.id}</span><span>${UI.esc(c.author || 'anonymous')}</span>${c.score ? `<span>· ${c.score} pts</span>` : ''}</div>
            <div class="txt">${UI.esc(c.text)}</div>
            ${h.why ? `<div class="why">${UI.esc(String(h.why).replace(/\u2014/g, ', '))}</div>` : ''}
          </button>`;
        }).join('');
        PG.jumpTo(hits[0].id);
      },
      onError: m => { job = null; hitsEl.innerHTML = `<div class="err">${UI.errorHtml(m)}</div>`; },
    });
  }

  function runSelection(action, text) {
    show();
    const labels = { explain: 'Explain selection', summarize: 'Summarize selection', translate: 'Translation', reply: 'Reply draft' };
    run({
      label: labels[action] || 'Result',
      quote: text.slice(0, 400),
      prompt: P.selection(action, text, settings),
      raw: action === 'reply' || action === 'translate',
      regenerate: () => runSelection(action, text),
    });
  }

  // ---------- events ----------
  root.querySelectorAll('.tile').forEach(t => t.addEventListener('click', () => runTask(t.dataset.task)));
  $('.close').addEventListener('click', hide);
  $('.reread').addEventListener('click', () => { snapshot(); });
  body.addEventListener('click', e => {
    const c = e.target.closest('[data-cid]');
    if (c) { e.preventDefault(); PG.jumpTo(c.dataset.cid); }
  });
  root.querySelectorAll('.seg button').forEach(b => b.addEventListener('click', () => {
    mode = b.dataset.mode;
    root.querySelectorAll('.seg button').forEach(x => x.classList.toggle('on', x === b));
    q.placeholder = mode === 'find' ? 'Describe the comments to find, e.g. people who disagree' : 'Ask anything about this page';
    q.focus();
  }));
  const submit = () => {
    const v = q.value.trim();
    if (!v) return;
    q.value = '';
    q.style.height = 'auto';
    mode === 'find' ? runFind(v) : runAsk(v);
  };
  go.addEventListener('click', submit);
  q.addEventListener('keydown', e => {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); submit(); }
    if (e.key === 'Escape') hide();
  });
  q.addEventListener('input', () => { q.style.height = 'auto'; q.style.height = Math.min(120, q.scrollHeight) + 'px'; });
  drawer.addEventListener('keydown', e => { if (e.key === 'Escape') hide(); });

  launcher.addEventListener('click', e => {
    if (e.target.closest('.dismiss')) {
      e.stopPropagation();
      try { sessionStorage.setItem('drafty-hide', location.pathname); } catch {}
      launcher.style.display = 'none';
      return;
    }
    show();
    runTask(page.comments.length >= 3 ? 'comments' : 'tldr');
  });

  try {
    chrome.runtime.onMessage.addListener((msg, _s, respond) => {
      if (msg.type === 'assistant') { open() ? hide() : show(); respond({ ok: true }); }
      if (msg.type === 'selection') { runSelection(msg.action, msg.text || String(getSelection())); respond({ ok: true }); }
    });
  } catch {}

  // ---------- launcher: appears on pages with discussions or long articles ----------
  let lastUrl = '';
  function checkLauncher(force = false) {
    if (open() || settings.launcher === false || NO_LAUNCHER.test(H) || A.chatAdapter()) { launcher.style.display = 'none'; return; }
    if (!force && location.href === lastUrl) return;
    lastUrl = location.href;
    let hidden = false;
    try { hidden = sessionStorage.getItem('drafty-hide') === location.pathname; } catch {}
    if (hidden) { launcher.style.display = 'none'; return; }
    const n = PG.quickCount();
    let label = '';
    if (n >= 5) label = PG.siteName() === H ? 'Summarize discussion' : `Summarize ${n} comments`;
    else if (document.querySelectorAll('article p, main p').length >= 8) label = 'Summarize page';
    if (!label) { launcher.style.display = 'none'; return; }
    themeEl.dataset.theme = UI.themeFor(document.body);
    $('.ltxt').textContent = label;
    launcher.style.display = 'flex';
  }

  setTimeout(() => checkLauncher(true), 2500);
  setInterval(() => {
    if (location.href !== lastUrl) setTimeout(() => checkLauncher(true), 1800);
    else if (launcher.style.display === 'none' && !open()) checkLauncher(true);
  }, 4000);
})();
