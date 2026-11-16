// Drafty shared UI: settings, theming, streaming, markdown.

(() => {
  if (window.DraftyUI) return;

  const settings = {
    auto: true, cleanup: true, launcher: true, aboutMe: '', myName: '',
    budgetTokens: 3000, halfLife: 10, assistantBudget: 12000,
    autoInsert: false, replyLang: 'Auto', translateTo: 'English', provider: 'claude',
  };
  try {
    chrome.storage.sync.get(settings).then(s => Object.assign(settings, s)).catch(() => {});
    chrome.storage.onChanged.addListener((ch, area) => {
      if (area === 'sync') for (const k in ch) settings[k] = ch[k].newValue;
    });
  } catch {}

  const TOKENS = `
    :host { all: initial; }
    * { box-sizing: border-box; font-family: "Inter", -apple-system, "Segoe UI", Roboto, "Helvetica Neue", sans-serif; }
    .theme {
      --accent: #D97757; --accent-ink: #ffffff; --accent-soft: rgba(217,119,87,.12);
      --bg: #ffffff; --bg2: #f7f5f2; --fg: #1d1d1f; --muted: #6e6e73; --line: #e7e3dd; --field: #fbfaf8;
      --shadow: 0 18px 48px rgba(20,20,20,.18), 0 2px 6px rgba(20,20,20,.06); --danger: #d1453b;
      color: var(--fg);
    }
    .theme[data-theme="dark"] {
      --bg: #1f2124; --bg2: #26292d; --fg: #ececee; --muted: #9a9ca3; --line: #34373c; --field: #191a1d;
      --accent-soft: rgba(217,119,87,.18); --shadow: 0 18px 48px rgba(0,0,0,.5);
    }
    button { font: inherit; }
    .md { font-size: 13.5px; line-height: 1.6; word-wrap: break-word; }
    .md h2 { font-size: 13px; text-transform: uppercase; letter-spacing: .04em; color: var(--muted); margin: 14px 0 6px; font-weight: 650; }
    .md h3 { font-size: 14px; margin: 12px 0 4px; }
    .md p { margin: 6px 0; }
    .md ul, .md ol { margin: 6px 0; padding-left: 20px; }
    .md li { margin: 3px 0; }
    .md code { background: var(--bg2); border-radius: 4px; padding: 1px 5px; font-family: ui-monospace, Consolas, monospace; font-size: 12px; }
    .md pre { background: var(--bg2); border-radius: 8px; padding: 10px; overflow: auto; }
    .md blockquote { margin: 6px 0; padding-left: 10px; border-left: 3px solid var(--line); color: var(--muted); }
    .md a { color: var(--accent); }
    .md a.cite {
      display: inline-block; text-decoration: none; background: var(--accent-soft); color: var(--accent);
      border-radius: 6px; padding: 0 6px; font-size: 11.5px; font-weight: 600; margin: 0 1px; cursor: pointer;
    }
    .md a.cite:hover { background: var(--accent); color: var(--accent-ink); }
    .md > :first-child { margin-top: 0; }
  `;

  function makeHost(tag, css, html) {
    const host = document.createElement(tag);
    host.style.cssText = 'all:initial; position:fixed; top:0; left:0; width:0; height:0; z-index:2147483647;';
    const root = host.attachShadow({ mode: 'open' });
    root.innerHTML = `<style>${TOKENS}${css}</style>${html}`;
    document.documentElement.appendChild(host);
    // Keep page shortcuts (e.g. "type anywhere to focus") from stealing keystrokes typed into Drafty.
    for (const t of ['keydown', 'keyup', 'keypress', 'input', 'paste', 'copy', 'cut', 'focusin', 'focusout']) {
      root.addEventListener(t, e => e.stopPropagation());
    }
    return { host, root };
  }

  function themeFor(el) {
    let n = el;
    while (n && n !== document.documentElement) {
      const bg = getComputedStyle(n).backgroundColor;
      const m = bg.match(/rgba?\(([\d.]+),\s*([\d.]+),\s*([\d.]+)(?:,\s*([\d.]+))?/);
      if (m && (m[4] === undefined || +m[4] > 0.5)) {
        return 0.2126 * m[1] + 0.7152 * m[2] + 0.0722 * m[3] < 128 ? 'dark' : 'light';
      }
      n = n.parentElement;
    }
    const docBg = getComputedStyle(document.documentElement).backgroundColor;
    const m = docBg.match(/rgba?\(([\d.]+),\s*([\d.]+),\s*([\d.]+)(?:,\s*([\d.]+))?/);
    if (m && (m[4] === undefined || +m[4] > 0.5)) return 0.2126 * m[1] + 0.7152 * m[2] + 0.0722 * m[3] < 128 ? 'dark' : 'light';
    return matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  }

  const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

  function errorHtml(m) {
    const login = m.code === 'auth' ? ' <a href="https://claude.ai/login" target="_blank" rel="noopener">Open claude.ai</a>' : '';
    return esc(m.message || 'Something went wrong.') + login;
  }

  // Streams a prompt through the background worker.
  function stream(prompt, h) {
    let port, done = false;
    try {
      port = chrome.runtime.connect({ name: 'drafty' });
    } catch {
      h.onError?.({ code: 'reload', message: 'Drafty was updated. Refresh this page to reconnect.' });
      return { stop() {} };
    }
    // h.onText(full) receives the whole text so far, for both streaming styles.
    let full = '';
    port.onMessage.addListener(m => {
      if (done) return;
      if (m.type === 'delta') { full += m.text; h.onText?.(full); }
      else if (m.type === 'replace') { full = m.text; h.onText?.(full); }
      else if (m.type === 'done') { done = true; h.onDone?.(full); try { port.disconnect(); } catch {} }
      else if (m.type === 'error') { done = true; h.onError?.(m); try { port.disconnect(); } catch {} }
    });
    port.onDisconnect.addListener(() => { if (!done) { done = true; h.onError?.({ code: 'disconnect', message: 'Connection to Drafty was lost. Try again.' }); } });
    port.postMessage({ type: 'generate', prompt, cleanup: settings.cleanup !== false });
    return { stop() { if (!done) { done = true; try { port.disconnect(); } catch {} } } };
  }

  // Minimal, safe markdown renderer. [C12] citations become jump links.
  function inline(s) {
    s = esc(s);
    s = s.replace(/`([^`]+)`/g, '<code>$1</code>');
    s = s.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
    s = s.replace(/(^|[^*])\*([^*\n]+)\*/g, '$1<em>$2</em>');
    s = s.replace(/\[([^\]]+)\]\((https?:\/\/[^)\s]+)\)/g, '<a href="$2" target="_blank" rel="noopener">$1</a>');
    s = s.replace(/\[((?:C\d+)(?:\s*,\s*C\d+)*)\]/g, (_, ids) =>
      ids.split(/\s*,\s*/).map(id => `<a class="cite" data-cid="${id}">${id}</a>`).join(''));
    s = s.replace(/\u2014/g, ', ');
    return s;
  }

  function md(text) {
    const lines = String(text || '').replace(/\r/g, '').split('\n');
    let html = '', list = null, para = [], code = null;
    const flushPara = () => { if (para.length) { html += `<p>${inline(para.join(' '))}</p>`; para = []; } };
    const flushList = () => { if (list) { html += `</${list}>`; list = null; } };
    for (const raw of lines) {
      if (code !== null) {
        if (/^```/.test(raw)) { html += `<pre><code>${esc(code)}</code></pre>`; code = null; } else code += raw + '\n';
        continue;
      }
      if (/^```/.test(raw)) { flushPara(); flushList(); code = ''; continue; }
      const line = raw.trimEnd();
      let m;
      if (!line.trim()) { flushPara(); flushList(); continue; }
      if ((m = line.match(/^(#{1,4})\s+(.*)$/))) {
        flushPara(); flushList();
        html += m[1].length <= 2 ? `<h2>${inline(m[2])}</h2>` : `<h3>${inline(m[2])}</h3>`;
        continue;
      }
      if ((m = line.match(/^\s*[-*•]\s+(.*)$/)) || (m = line.match(/^\s*\d+[.)]\s+(.*)$/))) {
        flushPara();
        const type = /^\s*\d/.test(line) ? 'ol' : 'ul';
        if (list !== type) { flushList(); html += `<${type}>`; list = type; }
        html += `<li>${inline(m[1])}</li>`;
        continue;
      }
      if ((m = line.match(/^>\s?(.*)$/))) { flushPara(); flushList(); html += `<blockquote>${inline(m[1])}</blockquote>`; continue; }
      flushList();
      para.push(line);
    }
    if (code !== null) html += `<pre><code>${esc(code)}</code></pre>`;
    flushPara(); flushList();
    return html;
  }

  const ICON = {
    spark: '<svg viewBox="0 0 24 24" width="16" height="16" fill="currentColor"><path d="M12 2l1.9 6.6L20.5 10.5l-6.6 1.9L12 19l-1.9-6.6L3.5 10.5l6.6-1.9z"/></svg>',
    close: '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M6 6l12 12M18 6L6 18"/></svg>',
    send: '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12h14M13 6l6 6-6 6"/></svg>',
    copy: '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="11" height="11" rx="2"/><path d="M5 15V5a2 2 0 0 1 2-2h10"/></svg>',
    refresh: '<svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12a9 9 0 1 1-3-6.7L21 8"/><path d="M21 3v5h-5"/></svg>',
    stop: '<svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor"><rect x="6" y="6" width="12" height="12" rx="2"/></svg>',
    search: '<svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="11" cy="11" r="7"/><path d="M20 20l-3.5-3.5"/></svg>',
    chat: '<svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12a8 8 0 0 1-11.6 7.1L4 20l1-4.6A8 8 0 1 1 21 12z"/></svg>',
    doc: '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/><path d="M14 3v5h5M9 13h6M9 17h4"/></svg>',
    comments: '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M17 8h2a2 2 0 0 1 2 2v9l-3-3h-6a2 2 0 0 1-2-2v-1"/><path d="M3 5a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H7l-4 4z"/></svg>',
    scale: '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3v18M5 21h14M6 7h12M6 7l-3 7a3 3 0 0 0 6 0zM18 7l-3 7a3 3 0 0 0 6 0z"/></svg>',
    star: '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round"><path d="M12 3l2.8 5.7 6.2.9-4.5 4.4 1.1 6.2L12 17.3 6.4 20.2l1.1-6.2L3 9.6l6.2-.9z"/></svg>',
    pen: '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z"/></svg>',
    bulb: '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M9 18h6M10 22h4M12 2a7 7 0 0 0-4 12.7V17h8v-2.3A7 7 0 0 0 12 2z"/></svg>',
  };

  window.DraftyUI = { settings, makeHost, themeFor, esc, errorHtml, stream, md, ICON };
})();
