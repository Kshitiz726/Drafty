// Drafty adapters: find the box you're typing in and decide what kind of reply it is.
//   chat    : WhatsApp, Instagram DMs, Messenger, Telegram, Discord, LinkedIn and X messages
//   email   : Gmail, Outlook
//   comment : everything else (Reddit, YouTube, Facebook, X replies, LinkedIn, forums, blogs...)

(() => {
  if (window.DraftyAdapters) return;

  const host = location.hostname;

  // ---------- DOM helpers ----------
  function visible(el) {
    if (!el || !el.isConnected) return false;
    const r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0 && r.bottom > 0 && r.top < innerHeight;
  }

  function isEditable(el) {
    if (!el || el.nodeType !== 1) return false;
    if (el.tagName === 'TEXTAREA') return !el.disabled && !el.readOnly;
    if (el.tagName === 'INPUT') return false;
    return el.isContentEditable;
  }

  const composedParent = el => el.parentElement || (el.getRootNode?.() instanceof ShadowRoot ? el.getRootNode().host : null);

  function composedClosest(el, sel) {
    for (let n = el; n; n = composedParent(n)) if (n.matches?.(sel)) return n;
    return null;
  }

  function composedContains(a, b) {
    for (let n = b; n; n = composedParent(n)) if (n === a) return true;
    return false;
  }

  function deepActive() {
    let a = document.activeElement;
    while (a?.shadowRoot?.activeElement) a = a.shadowRoot.activeElement;
    return a;
  }

  // Climb from a focused node to the actual editable root.
  function editableRoot(el) {
    if (!el || el.nodeType !== 1) return null;
    if (el.tagName === 'TEXTAREA') return isEditable(el) ? el : null;
    if (!el.isContentEditable) return null;
    let root = el;
    for (let p = composedParent(el); p && p.isContentEditable; p = composedParent(p)) root = p;
    return root;
  }

  function isComposerLike(el) {
    if (!isEditable(el)) return false;
    const role = (el.getAttribute('role') || '').toLowerCase();
    if (role === 'searchbox' || role === 'combobox') return false;
    if (composedClosest(el, 'drafty-root, drafty-assistant')) return false;
    const r = el.getBoundingClientRect();
    return r.width >= 140 && r.height >= 16;
  }

  // Text with emoji <img alt> preserved. `skip` excludes matching descendants.
  function textOf(node, skip) {
    if (!node) return '';
    let out = '';
    const walk = n => {
      if (n.nodeType === 3) { out += n.nodeValue; return; }
      if (n.nodeType !== 1) return;
      if (n !== node && skip && n.matches?.(skip)) return;
      const tag = n.tagName;
      if (tag === 'IMG') { if (n.alt && [...n.alt].length <= 4) out += n.alt; return; }
      if (tag === 'BR') { out += '\n'; return; }
      if (tag === 'SCRIPT' || tag === 'STYLE' || tag === 'SVG' || tag === 'svg' || tag === 'BUTTON' || tag === 'NOSCRIPT') return;
      const block = /^(P|DIV|LI|H[1-6]|BLOCKQUOTE|PRE|TR|SECTION|ARTICLE)$/.test(tag);
      if (block && out && !out.endsWith('\n')) out += '\n';
      for (const c of n.childNodes) walk(c);
      if (n.shadowRoot) for (const c of n.shadowRoot.childNodes) walk(c);
      if (block && !out.endsWith('\n')) out += '\n';
    };
    walk(node);
    return out.replace(/[ \t ]+/g, ' ').replace(/ ?\n ?/g, '\n').replace(/\n{3,}/g, '\n\n').trim();
  }

  const NOISE = /^(seen|seen by .*|delivered|sent|read|edited|active now|active \d+\s?\w+ ago|typing\.*|today|yesterday|you sent|you replied.*|reply|replied to .*|liked a message|enter|\d{1,2}[:.]\d{2}(\s?[ap]\.?m\.?)?|\d{1,2}\/\d{1,2}\/\d{2,4}|(mon|tues|wednes|thurs|fri|satur|sun)day)$/i;

  function composerLabel(el) {
    return ((el.getAttribute('aria-label') || '') + ' ' + (el.getAttribute('aria-placeholder') || '') + ' ' +
      (el.getAttribute('placeholder') || '') + ' ' + (el.getAttribute('data-placeholder') || '')).toLowerCase();
  }

  function findComposerGeneric(accept = () => true) {
    const ae = editableRoot(deepActive());
    if (ae && visible(ae) && accept(ae)) return ae;
    let best = null, bestScore = -Infinity;
    for (const el of document.querySelectorAll('[contenteditable="true"], [contenteditable=""], [contenteditable="plaintext-only"], textarea, [role="textbox"]')) {
      if (!isEditable(el) || !visible(el) || !accept(el)) continue;
      if (el.parentElement?.closest('[contenteditable="true"]')) continue;
      const r = el.getBoundingClientRect();
      const label = composerLabel(el);
      const score = r.bottom / innerHeight + (/messag|reply|chat|write|aa\b|send/.test(label) ? 1 : 0) + (r.width > 200 ? 0.3 : 0);
      if (score > bestScore) { bestScore = score; best = el; }
    }
    return best;
  }

  function findThread(composer) {
    if (!composer) return null;
    const cr = composer.getBoundingClientRect();
    let best = null, bestScore = 0;
    for (const el of document.querySelectorAll('div, section, main, ul, ol')) {
      if (el.scrollHeight <= el.clientHeight + 40 || el.clientHeight < 120) continue;
      const oy = getComputedStyle(el).overflowY;
      if (oy !== 'auto' && oy !== 'scroll' && oy !== 'overlay') continue;
      if (el.contains(composer)) continue;
      const r = el.getBoundingClientRect();
      if (r.top > cr.top || r.width < 150) continue;
      const overlap = Math.min(r.right, cr.right) - Math.max(r.left, cr.left);
      if (overlap < cr.width * 0.5) continue;
      const distance = Math.max(0, cr.top - r.bottom);
      const score = (r.height * overlap) / (1 + distance / 40);
      if (score > bestScore) { bestScore = score; best = el; }
    }
    return best;
  }

  // Geometry: bubble aligned right = me, left = them, centered = notice.
  function extractGeometric(container) {
    if (!container) return [];
    const cr = container.getBoundingClientRect();
    const styleCache = new Map();
    const display = el => {
      if (!styleCache.has(el)) styleCache.set(el, getComputedStyle(el).display);
      return styleCache.get(el);
    };
    const blockOf = el => {
      while (el && el !== container) {
        const d = display(el);
        if (!d.startsWith('inline') && d !== 'contents') return el;
        el = el.parentElement;
      }
      return container;
    };
    const parts = new Map();
    const add = (block, s) => {
      if (!parts.has(block)) parts.set(block, []);
      parts.get(block).push(s);
    };
    const walker = document.createTreeWalker(container, NodeFilter.SHOW_TEXT | NodeFilter.SHOW_ELEMENT);
    for (let n = walker.nextNode(); n; n = walker.nextNode()) {
      if (n.nodeType === 1) {
        if (n.tagName === 'IMG' && n.alt && [...n.alt].length <= 4 && !n.closest('button,[role="button"],[aria-hidden="true"]')) add(blockOf(n.parentElement), n.alt);
        continue;
      }
      const v = n.nodeValue;
      if (!v || !v.trim()) continue;
      const p = n.parentElement;
      if (!p || p.closest('button, [role="button"], [aria-hidden="true"], time, script, style, svg, [role="heading"]')) continue;
      add(blockOf(p), v);
    }
    const cw = cr.width;
    const items = [];
    for (const [block, strs] of parts) {
      const text = strs.join('').replace(/\s+\n/g, '\n').trim();
      if (!text || NOISE.test(text)) continue;
      let bubble = block, el = block;
      while (el && el !== container) {
        const w = el.getBoundingClientRect().width;
        if (w > 0 && w < cw * 0.8) bubble = el; else break;
        el = el.parentElement;
      }
      const br = bubble.getBoundingClientRect();
      if (!br.width || !br.height) continue;
      const left = br.left - cr.left, right = cr.right - br.right;
      const who = right < left * 0.5 ? 'me' : left < right * 0.5 ? 'them' : 'system';
      if (who === 'system' && text.length < 25) continue;
      items.push({ bubble, top: br.top, left: br.left, who, text });
    }
    items.sort((a, b) => a.top - b.top || a.left - b.left);
    const out = [];
    for (const it of items) {
      const prev = out[out.length - 1];
      if (prev && prev._bubble === it.bubble) prev.text += '\n' + it.text;
      else out.push({ who: it.who, text: it.text, ts: null, time: null, _bubble: it.bubble });
    }
    return out.map(({ _bubble, ...m }) => m);
  }

  function headerTitle(sel) {
    const el = document.querySelector(sel);
    return el ? textOf(el).split('\n')[0].slice(0, 80) : '';
  }

  // ---------- chat: WhatsApp ----------
  function parseWaPre(pre) {
    const m = pre.match(/^\[(.+?)\]\s*(.*?):\s*$/);
    if (!m) return { time: null, ts: null, name: null };
    const [time, date] = m[1].split(/,\s*/);
    let ts = null;
    try {
      const tm = time.match(/(\d{1,2})[:.](\d{2})\s*([ap])?/i);
      const dm = (date || '').match(/(\d{1,4})[\/.\-](\d{1,2})[\/.\-](\d{1,4})/);
      if (tm && dm) {
        let a = +dm[1], b = +dm[2], y = +dm[3], d, mo;
        if (a > 31) { y = a; mo = b; d = +dm[3]; }
        else if (a > 12) { d = a; mo = b; }
        else if (b > 12) { mo = a; d = b; }
        else if ((navigator.language || '').toLowerCase() === 'en-us') { mo = a; d = b; }
        else { d = a; mo = b; }
        if (y < 100) y += 2000;
        let h = +tm[1];
        if (tm[3]) { const pm = tm[3].toLowerCase() === 'p'; if (pm && h < 12) h += 12; if (!pm && h === 12) h = 0; }
        ts = new Date(y, mo - 1, d, h, +tm[2]).getTime();
      }
    } catch {}
    return { time: time || null, ts, name: m[2] || null };
  }

  const whatsapp = {
    platform: 'WhatsApp',
    enabled: () => true,
    composer: () => document.querySelector('#main footer [contenteditable="true"]'),
    title: () => headerTitle('#main header span[dir="auto"]'),
    messages() {
      const main = document.querySelector('#main');
      if (!main) return [];
      const mr = main.getBoundingClientRect();
      let rows = [...main.querySelectorAll('.message-in, .message-out')];
      if (!rows.length) rows = [...main.querySelectorAll('[data-pre-plain-text]')];
      const out = [];
      for (const row of rows) {
        const copy = row.matches('[data-pre-plain-text]') ? row : row.querySelector('[data-pre-plain-text]');
        let who = row.closest('.message-out') ? 'me' : row.closest('.message-in') ? 'them' : null;
        if (!who) {
          const r = row.getBoundingClientRect();
          who = (mr.right - r.right) < (r.left - mr.left) ? 'me' : 'them';
        }
        if (!copy) { out.push({ who, text: '[media, voice note or sticker]', ts: null, time: null }); continue; }
        const meta = parseWaPre(copy.getAttribute('data-pre-plain-text') || '');
        const spans = copy.querySelectorAll('span.selectable-text');
        let text = spans.length ? textOf(spans[spans.length - 1]) : textOf(copy);
        const quoted = copy.querySelector('.quoted-mention');
        if (quoted && spans.length) text = `(replying to "${textOf(quoted).slice(0, 120)}") ${text}`;
        if (!text) continue;
        out.push({ who, name: who === 'them' ? meta.name : null, text, ts: meta.ts, time: meta.time });
      }
      return out;
    },
  };

  // ---------- chat: Discord ----------
  const discord = {
    platform: 'Discord',
    enabled: () => true,
    composer: () => document.querySelector('[role="textbox"][contenteditable="true"][data-slate-editor="true"]') || findComposerGeneric(),
    title: () => headerTitle('section[aria-label] h1, h1'),
    messages(settings) {
      const me = (settings.myName || '').trim().toLowerCase();
      let author = null;
      const out = [];
      for (const li of document.querySelectorAll('li[id^="chat-messages-"]')) {
        const u = li.querySelector('[id^="message-username-"]');
        if (u) author = textOf(u).replace(/\s+/g, ' ').trim();
        const c = li.querySelector('[id^="message-content-"]');
        const text = c ? textOf(c) : '';
        if (!text) continue;
        const t = li.querySelector('time[datetime]');
        const ts = t ? Date.parse(t.getAttribute('datetime')) : null;
        const who = me && author && author.toLowerCase().includes(me) ? 'me' : 'them';
        out.push({ who, name: who === 'them' ? author : null, text, ts, time: t ? new Date(ts).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : null });
      }
      return out;
    },
  };

  // ---------- chat: geometry based ----------
  function geometricChat(platform, enabled, accept) {
    let cachedThread = null, cachedFor = null;
    return {
      platform,
      enabled,
      composer: () => findComposerGeneric(accept),
      title: () => {
        const h = document.querySelector('main header h1, main header h2, [role="main"] h2, header h1, h1');
        return h ? textOf(h).split('\n')[0].slice(0, 80) : document.title.replace(/\s*[|•-].*$/, '').slice(0, 80);
      },
      messages(_s, composer) {
        const c = composer || findComposerGeneric(accept);
        if (c !== cachedFor || !cachedThread?.isConnected) { cachedThread = findThread(c); cachedFor = c; }
        return extractGeometric(cachedThread);
      },
    };
  }

  const msgLabel = el => /messag|mensa|nachricht|aa\b/.test(composerLabel(el)) || !!el.closest('[aria-label*="chat" i]');

  // Page-level chat adapter (keeps the button pinned to the message box and enables auto-draft).
  function chatAdapter() {
    const p = location.pathname;
    if (host === 'web.whatsapp.com') return whatsapp;
    if (host === 'discord.com') return discord;
    if (host === 'web.telegram.org') return geometricChat('Telegram', () => true);
    if (host.endsWith('messenger.com')) return geometricChat('Messenger', () => true);
    if (host.endsWith('instagram.com')) return geometricChat('Instagram DM', () => location.pathname.startsWith('/direct'));
    if (host.endsWith('linkedin.com') && p.startsWith('/messaging')) return geometricChat('LinkedIn messages', () => location.pathname.startsWith('/messaging'));
    if ((host === 'x.com' || host === 'twitter.com') && (p.startsWith('/messages') || p.startsWith('/i/chat'))) {
      return geometricChat('X direct messages', () => /^\/(messages|i\/chat)/.test(location.pathname));
    }
    return null;
  }

  // ---------- email: Gmail ----------
  const gmail = {
    platform: 'Gmail',
    account() {
      const m = document.title.match(/[\w.+-]+@[\w-]+(\.[\w-]+)+/);
      return m ? m[0].toLowerCase() : '';
    },
    subject: () => textOf(document.querySelector('h2.hP')),
    messages() {
      const me = this.account();
      let items = [...document.querySelectorAll('[role="list"] > [role="listitem"]')].filter(i => i.querySelector('[email]'));
      if (!items.length) items = [...document.querySelectorAll('div.adn')];
      const out = [];
      for (const item of items) {
        const sender = item.querySelector('.gD[email], span[email]');
        const email = (sender?.getAttribute('email') || '').toLowerCase();
        const name = sender?.getAttribute('name') || textOf(sender);
        const body = item.querySelector('.a3s');
        let text = body ? textOf(body, '.gmail_quote, blockquote, .adL .im') : textOf(item.querySelector('.iA.g6, .g6, .y2'));
        if (!text) continue;
        const dateEl = item.querySelector('.g3[title], span[title][alt]');
        const when = dateEl?.getAttribute('title') || '';
        const ts = Date.parse(when.replace(/,/g, '')) || null;
        out.push({ who: me && email === me ? 'me' : 'them', name, email, text, ts, time: when || null });
      }
      return out;
    },
  };

  // ---------- email: Outlook ----------
  const outlook = {
    platform: 'Outlook',
    subject: () => textOf(document.querySelector('[role="main"] [role="heading"][aria-level="2"], [role="main"] h2, [role="main"] [role="heading"]')),
    messages() {
      const myName = (document.title.split(' - ')[1] || '').trim().toLowerCase();
      let bodies = [...document.querySelectorAll('div[aria-label="Message body"], div[role="document"]')]
        .filter(b => !b.isContentEditable && !b.querySelector('[contenteditable="true"]'));
      bodies = bodies.filter(b => !bodies.some(o => o !== b && o.contains(b)));
      const out = [];
      for (const b of bodies) {
        const item = b.closest('[data-convid], [role="listitem"], [aria-expanded]') || b.parentElement?.parentElement?.parentElement;
        const sender = item?.querySelector('span[title*="@"], [aria-label^="From" i]');
        const name = sender ? textOf(sender).split('\n')[0] : '';
        const text = textOf(b, 'blockquote, #divRplyFwdMsg, #appendonsend ~ *, .gmail_quote');
        if (!text) continue;
        const who = myName && name && name.toLowerCase().includes(myName) ? 'me' : 'them';
        out.push({ who, name, text, ts: null, time: null });
      }
      return out;
    },
  };

  // ---------- context builders ----------
  function hash(str) {
    let h = 5381;
    for (let i = 0; i < str.length; i++) h = ((h << 5) + h + str.charCodeAt(i)) | 0;
    return (h >>> 0).toString(36);
  }

  // full = true sends the whole loaded conversation (Analyze, Summarize).
  function chatContext(adapter, composer, settings, full = false) {
    let msgs = [];
    try { msgs = adapter.messages(settings, composer) || []; } catch (e) { console.warn('[Drafty]', e); }
    if (!msgs.length) return { error: "Couldn't read any messages here. Open a chat and scroll it into view, then try again." };
    const C = window.DraftyCtx;
    const { selected, stats } = C.select(msgs, full
      ? { budgetTokens: +settings.assistantBudget || 12000, coverage: 1.01, minMessages: msgs.length, maxMsgChars: 3000 }
      : { budgetTokens: +settings.budgetTokens || 3000, halfLife: +settings.halfLife || 10 });
    const title = adapter.title();
    return {
      kind: 'chat',
      platform: adapter.platform,
      title,
      transcript: C.format(selected, title),
      stats,
      lastIsMe: msgs[msgs.length - 1].who === 'me',
      sig: 'chat#' + hash(title + '|' + msgs.length + '|' + msgs.slice(-6).map(m => m.who + ':' + m.text).join('|')),
      statsText: `${stats.used}/${stats.total} msgs · ~${stats.tokens} tok · ${Math.round(Math.min(1, stats.coverage) * 100)}% relevance`,
    };
  }

  function emailContext(adapter, settings, full = false) {
    const msgs = adapter.messages();
    const subject = adapter.subject();
    if (!msgs.length) {
      return { kind: 'email', platform: adapter.platform, title: subject, transcript: '', stats: { used: 0, total: 0, tokens: 0, omitted: 0 }, lastIsMe: false, sig: 'email#new#' + hash(subject), statsText: 'New email' };
    }
    const C = window.DraftyCtx;
    const { selected, stats } = C.select(msgs, full
      ? { budgetTokens: +settings.assistantBudget || 12000, coverage: 1.01, minMessages: msgs.length, maxMsgChars: 6000 }
      : { budgetTokens: Math.round((+settings.budgetTokens || 3000) * 1.5), halfLife: 4, minMessages: 3, maxMsgChars: 4000 });
    const transcript = selected.map(m =>
      `From: ${m.who === 'me' ? 'Me' : (m.name || 'Them')}${m.time ? ` (${m.time})` : ''}\n${m.text}`).join('\n\n---\n\n');
    return {
      kind: 'email',
      platform: adapter.platform,
      title: subject,
      transcript,
      stats,
      lastIsMe: msgs[msgs.length - 1].who === 'me',
      sig: 'email#' + hash(subject + '|' + msgs.length + '|' + msgs[msgs.length - 1].text),
      statsText: `${stats.used}/${stats.total} emails · ~${stats.tokens} tok`,
    };
  }

  // Resolve which mode applies to a given text box.
  function forComposer(el) {
    const chat = chatAdapter();
    const mk = (a, kind = 'chat') => ({ kind, platform: a.platform, context: (s, full) => chatContext(a, el, s, full) });
    if (chat && chat.enabled()) return mk(chat);
    if (host.endsWith('facebook.com') && msgLabel(el)) return mk(geometricChat('Facebook Messenger', () => true, x => x === el));
    if (host === 'mail.google.com') return { kind: 'email', platform: 'Gmail', context: (s, full) => emailContext(gmail, s, full) };
    if (/^outlook\.(live|office|office365)\.com$/.test(host)) return { kind: 'email', platform: 'Outlook', context: (s, full) => emailContext(outlook, s, full) };

    // Unknown sites: if the area above the box looks like a two-sided chat, treat it as one.
    if (window.DraftyPage.siteName() === host) {
      const name = host.replace(/^www\./, '').replace(/\.(com|ai|io|app|net|org)$/, '');
      const geo = geometricChat(name.charAt(0).toUpperCase() + name.slice(1), () => true, x => x === el);
      let msgs = [];
      try { msgs = geo.messages(null, el); } catch {}
      if (msgs.length >= 2 && msgs.some(m => m.who === 'me') && msgs.some(m => m.who === 'them')) return mk(geo);
    }
    return { kind: 'comment', platform: window.DraftyPage.siteName(), context: (s, full) => window.DraftyPage.replyContext(el, s, full) };
  }

  window.DraftyAdapters = {
    chatAdapter, forComposer, textOf, isEditable, visible, editableRoot, isComposerLike,
    composedClosest, composedContains, deepActive,
  };
})();
