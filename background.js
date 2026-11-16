// Drafty background worker. No API keys and no visible tabs.
// Claude: direct calls to claude.ai with your signed-in session.
// Gemini: direct calls to gemini.google.com with your signed-in Google session.
// ChatGPT, Grok: their own web app runs in a hidden frame (offscreen document), signed in as you.

const ROOT_PARENT = '00000000-0000-4000-8000-000000000000';
const CONTENT_FILES = [
  'content/context.js', 'content/adapters.js', 'content/page.js', 'content/prompts.js',
  'content/ui.js', 'content/content.js', 'content/assistant.js',
];

const PROVIDERS = {
  claude: { name: 'Claude', login: 'https://claude.ai/login' },
  chatgpt: {
    name: 'ChatGPT', login: 'https://chatgpt.com/auth/login', frame: 'https://chatgpt.com/?temporary-chat=true',
    cookies: { url: 'https://chatgpt.com', names: ['__Secure-next-auth.session-token', '__Secure-next-auth.session-token.0'] },
  },
  gemini: {
    name: 'Gemini', login: 'https://gemini.google.com/app',
    cookies: { url: 'https://gemini.google.com', names: ['__Secure-1PSID', 'SID'] },
  },
  grok: {
    name: 'Grok', login: 'https://accounts.x.ai/sign-in', frame: 'https://grok.com/',
    cookies: { url: 'https://grok.com', names: ['sso', 'sso-rw'] },
  },
};

const getProvider = async () => { const p = (await chrome.storage.sync.get({ provider: 'claude' })).provider; return p === 'gemini' ? 'gemini' : 'claude'; };

function err(code, message) {
  const e = new Error(message);
  e.code = code;
  return e;
}

// ---------------- network rules ----------------
async function installRules() {
  try {
    await chrome.declarativeNetRequest.updateSessionRules({
      removeRuleIds: [1, 2, 3],
      addRules: [
        { // claude.ai expects first-party requests
          id: 1, priority: 1,
          action: { type: 'modifyHeaders', requestHeaders: [
            { header: 'origin', operation: 'set', value: 'https://claude.ai' },
            { header: 'referer', operation: 'set', value: 'https://claude.ai/new' },
          ] },
          condition: { urlFilter: '|https://claude.ai/api/', tabIds: [chrome.tabs.TAB_ID_NONE], resourceTypes: ['xmlhttprequest', 'other'] },
        },
        { // Gemini expects its own origin
          id: 2, priority: 1,
          action: { type: 'modifyHeaders', requestHeaders: [
            { header: 'origin', operation: 'set', value: 'https://gemini.google.com' },
            { header: 'referer', operation: 'set', value: 'https://gemini.google.com/' },
          ] },
          condition: { requestDomains: ['gemini.google.com'], tabIds: [chrome.tabs.TAB_ID_NONE], resourceTypes: ['xmlhttprequest', 'other'] },
        },
        { // allow ChatGPT and Grok to load inside Drafty's hidden frame
          id: 3, priority: 1,
          action: { type: 'modifyHeaders', responseHeaders: [
            { header: 'x-frame-options', operation: 'remove' },
            { header: 'content-security-policy', operation: 'remove' },
            { header: 'content-security-policy-report-only', operation: 'remove' },
          ] },
          condition: { requestDomains: ['chatgpt.com', 'grok.com'], tabIds: [chrome.tabs.TAB_ID_NONE], resourceTypes: ['sub_frame'] },
        },
      ],
    });
  } catch (e) { console.warn('[Drafty] rules', e); }
}
const rulesReady = installRules();
chrome.runtime.onStartup.addListener(installRules);

// ---------------- Claude ----------------
async function claudeApi(path, { method = 'GET', body, signal, accept = 'application/json' } = {}) {
  await rulesReady;
  return fetch('https://claude.ai/api' + path, {
    method, signal, credentials: 'include',
    headers: { 'content-type': 'application/json', accept },
    body: body ? JSON.stringify(body) : undefined,
  });
}

async function claudeFail(res, what) {
  let text = '';
  try { text = await res.text(); } catch {}
  if (res.status === 401 || res.status === 403) {
    orgCache = null;
    throw err('auth', 'Not signed in to Claude. Open claude.ai, sign in, then try again.');
  }
  if (res.status === 429) {
    let msg = 'You hit your Claude usage limit. It resets in a few hours.';
    try {
      const inner = JSON.parse(text)?.error?.message;
      const parsed = typeof inner === 'string' && inner.startsWith('{') ? JSON.parse(inner) : null;
      if (parsed?.resetsAt) msg = `Claude usage limit reached. Resets at ${new Date(parsed.resetsAt * 1000).toLocaleTimeString()}.`;
    } catch {}
    throw err('rate', msg);
  }
  throw err('http', `${what} failed (HTTP ${res.status}).`);
}

let orgCache = null;

async function getOrg(force = false) {
  if (orgCache && !force) return orgCache;
  if (!force) {
    const { org } = await chrome.storage.session.get('org').catch(() => ({}));
    if (org) return (orgCache = org);
  }
  const res = await claudeApi('/organizations');
  if (!res.ok) await claudeFail(res, 'Loading your Claude account');
  const orgs = await res.json();
  if (!Array.isArray(orgs) || !orgs.length) throw err('auth', 'No Claude account found. Sign in at claude.ai.');
  const org = orgs.find(o => (o.capabilities || []).includes('chat')) || orgs[0];
  const caps = org.capabilities || [];
  const plan = caps.includes('claude_max') ? 'Max' : caps.includes('claude_pro') ? 'Pro' : caps.includes('raven') ? 'Team' : 'Free';
  orgCache = { uuid: org.uuid, name: org.name, plan };
  chrome.storage.session.set({ org: orgCache }).catch(() => {});
  return orgCache;
}

async function claudeGenerate(prompt, { signal, cleanup = true, onDelta }) {
  const org = await getOrg();
  if (cleanup) claudeSweep(org);
  const convId = crypto.randomUUID();
  let res = await claudeApi(`/organizations/${org.uuid}/chat_conversations`, { method: 'POST', signal, body: { uuid: convId, name: 'Drafty' } });
  if (!res.ok) await claudeFail(res, 'Starting a Claude chat');
  try {
    res = await claudeApi(`/organizations/${org.uuid}/chat_conversations/${convId}/completion`, {
      method: 'POST', signal, accept: 'text/event-stream',
      body: {
        prompt, parent_message_uuid: ROOT_PARENT,
        timezone: Intl.DateTimeFormat().resolvedOptions().timeZone, locale: 'en-US',
        attachments: [], files: [], sync_sources: [], rendering_mode: 'messages',
      },
    });
    if (!res.ok) await claudeFail(res, 'Asking Claude');
    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buf = '';
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      buf += decoder.decode(value, { stream: true });
      let nl;
      while ((nl = buf.indexOf('\n')) >= 0) {
        const line = buf.slice(0, nl).trim();
        buf = buf.slice(nl + 1);
        if (!line.startsWith('data:')) continue;
        const data = line.slice(5).trim();
        if (!data || data === '[DONE]') continue;
        let d;
        try { d = JSON.parse(data); } catch { continue; }
        if (d.type === 'content_block_delta' && d.delta?.type === 'text_delta') onDelta(d.delta.text);
        else if (d.type === 'completion' && d.completion) onDelta(d.completion);
        else if (d.type === 'error') throw err(d.error?.type === 'rate_limit_error' ? 'rate' : 'api', d.error?.message || 'Claude returned an error.');
      }
    }
  } finally {
    if (cleanup) await claudeRemove(org, convId);
  }
}

async function claudeRemove(org, id) {
  for (let t = 0; t < 2; t++) {
    try { const r = await claudeApi(`/organizations/${org.uuid}/chat_conversations/${id}`, { method: 'DELETE' }); if (r.ok || r.status === 404) return; } catch {}
    await new Promise(r => setTimeout(r, 800));
  }
}

/** Leftover Drafty chats (always named "Drafty") from interrupted replies. */
async function claudeSweep(org) {
  try {
    const r = await claudeApi(`/organizations/${org.uuid}/chat_conversations?limit=40`);
    if (!r.ok) return;
    for (const c of await r.json()) {
      if (c.name === 'Drafty' && Date.now() - Date.parse(c.updated_at || c.created_at || 0) > 60000) await claudeRemove(org, c.uuid);
    }
  } catch {}
}

// ---------------- Gemini (direct) ----------------
let geminiTokens = null;

async function geminiAuth(signal, force = false) {
  if (geminiTokens && !force && Date.now() - geminiTokens.at < 20 * 60000) return geminiTokens;
  await rulesReady;
  const res = await fetch('https://gemini.google.com/app', { credentials: 'include', signal });
  const html = await res.text();
  const at = (html.match(/"SNlM0e":"(.*?)"/) || [])[1];
  if (!at) throw err('auth', 'Not signed in to Gemini. Open gemini.google.com, sign in, then try again.');
  geminiTokens = {
    at: Date.now(),
    token: at,
    bl: (html.match(/"cfb2h":"(.*?)"/) || [])[1] || '',
    sid: (html.match(/"FdrFJe":"(.*?)"/) || [])[1] || '',
  };
  return geminiTokens;
}

function geminiText(raw) {
  // Response: )]}' prefix, then length-prefixed JSON chunks. Candidate text sits at inner[4][0][1][0].
  let best = '';
  for (const line of raw.split('\n')) {
    const l = line.trim();
    if (!l.startsWith('[')) continue;
    let outer;
    try { outer = JSON.parse(l); } catch { continue; }
    for (const part of outer) {
      if (!Array.isArray(part) || part[0] !== 'wrb.fr' || typeof part[2] !== 'string') continue;
      try {
        const inner = JSON.parse(part[2]);
        const t = inner?.[4]?.[0]?.[1]?.[0];
        if (typeof t === 'string' && t.length >= best.length) best = t;
      } catch {}
    }
  }
  return best;
}

async function geminiGenerate(prompt, { signal, onReplace, cleanup = true }, retry = true) {
  const tk = await geminiAuth(signal, !retry);
  const qs = new URLSearchParams({ bl: tk.bl, _reqid: String(100000 + Math.floor(Math.random() * 800000)), rt: 'c' });
  if (tk.sid) qs.set('f.sid', tk.sid);
  const body = new URLSearchParams({ 'f.req': JSON.stringify([null, JSON.stringify([[prompt, 0, null, null, null, null, 0], null, null])]), at: tk.token });
  const res = await fetch(`https://gemini.google.com/_/BardChatUi/data/assistant.lamda.BardFrontendService/StreamGenerate?${qs}`, {
    method: 'POST', credentials: 'include', signal,
    headers: { 'content-type': 'application/x-www-form-urlencoded;charset=UTF-8', 'x-same-domain': '1' },
    body,
  });
  if (res.status === 401 || res.status === 403 || res.status === 400) {
    if (retry) { geminiTokens = null; return geminiGenerate(prompt, { signal, onReplace, cleanup }, false); }
    throw err('auth', 'Gemini refused the request. Open gemini.google.com, make sure you are signed in, then retry.');
  }
  if (res.status === 429) throw err('rate', 'Gemini usage limit reached. Try again later.');
  if (!res.ok) throw err('http', `Gemini request failed (HTTP ${res.status}).`);
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let raw = '', last = '';
  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    raw += decoder.decode(value, { stream: true });
    const t = geminiText(raw);
    if (t && t !== last) { last = t; onReplace(t); }
  }
  const t = geminiText(raw);
  if (t && t !== last) onReplace(t);
  if (cleanup) await geminiDelete(tk, geminiChatId(raw));
  if (!t && !last) throw err('driver', 'Gemini returned no answer. Open gemini.google.com once, then retry.');
}

function geminiChatId(raw) {
  for (const line of raw.split(String.fromCharCode(10))) {
    const l = line.trim();
    if (!l.startsWith('[')) continue;
    let outer; try { outer = JSON.parse(l); } catch { continue; }
    for (const part of outer) {
      if (!Array.isArray(part) || part[0] !== 'wrb.fr' || typeof part[2] !== 'string') continue;
      try { const id = JSON.parse(part[2])?.[1]?.[0]; if (typeof id === 'string' && id.startsWith('c_')) return id; } catch {}
    }
  }
  return null;
}

/** Deletes the chat (title and messages) from Gemini history. Failed ids are retried next time. */
async function geminiDelete(tk, id) {
  const { geminiPending = [] } = await chrome.storage.local.get('geminiPending');
  const ids = [...new Set([...geminiPending, ...(id ? [id] : [])])];
  const failed = [];
  for (const cid of ids) {
    try {
      const qs = new URLSearchParams({ rpcids: 'GzXR5e', bl: tk.bl, _reqid: String(100000 + Math.floor(Math.random() * 800000)), rt: 'c', 'source-path': '/app' });
      if (tk.sid) qs.set('f.sid', tk.sid);
      const body = new URLSearchParams({ 'f.req': JSON.stringify([[['GzXR5e', JSON.stringify([cid]), null, 'generic']]]), at: tk.token });
      const r = await fetch('https://gemini.google.com/_/BardChatUi/data/batchexecute?' + qs, {
        method: 'POST', credentials: 'include',
        headers: { 'content-type': 'application/x-www-form-urlencoded;charset=UTF-8', 'x-same-domain': '1' }, body,
      });
      if (!r.ok || !(await r.text()).includes('GzXR5e')) failed.push(cid);
    } catch { failed.push(cid); }
  }
  await chrome.storage.local.set({ geminiPending: failed });
}

// ---------------- ChatGPT, Grok (hidden frame) ----------------
const drvJobs = new Map();
let frameWaiter = null;
let drvChain = Promise.resolve();

async function ensureOffscreen() {
  if (await chrome.offscreen.hasDocument?.()) return;
  try {
    await chrome.offscreen.createDocument({
      url: 'offscreen.html',
      reasons: ['IFRAME_SCRIPTING'],
      justification: 'Runs your signed-in ChatGPT or Grok session in the background without opening a tab.',
    });
  } catch (e) {
    if (!String(e.message).includes('single offscreen')) throw e;
  }
}

function frameGenerate(p, prompt, { signal, onReplace }) {
  const run = async () => {
    await rulesReady;
    await ensureOffscreen();
    const name = PROVIDERS[p].name;
    const port = await new Promise((resolve, reject) => {
      const timer = setTimeout(() => { frameWaiter = null; reject(err('driver', `${name} did not load. Open ${PROVIDERS[p].login} once in a normal tab to sign in, then retry.`)); }, 35000);
      frameWaiter = { p, resolve: port => { clearTimeout(timer); resolve(port); } };
      chrome.runtime.sendMessage({ type: 'offscreen-load', url: PROVIDERS[p].frame }).catch(() => {});
    });
    const id = crypto.randomUUID();
    return new Promise((resolve, reject) => {
      drvJobs.set(id, { onReplace, resolve, reject });
      port.onDisconnect.addListener(() => {
        if (drvJobs.has(id)) { drvJobs.delete(id); reject(err('driver', `${name} closed unexpectedly. Try again.`)); }
      });
      signal?.addEventListener('abort', () => {
        drvJobs.delete(id);
        try { port.postMessage({ type: 'cancel', id }); } catch {}
        reject(err('abort', 'Stopped.'));
      });
      port.postMessage({ type: 'run', id, prompt });
    });
  };
  const job = drvChain.then(run, run);
  drvChain = job.catch(() => {});
  return job;
}

// ---------------- status ----------------
async function providerStatus(p) {
  if (p === 'claude') {
    try { const org = await getOrg(true); return { connected: true, detail: `Claude ${org.plan}` }; }
    catch (e) { return { connected: false, detail: e.code === 'auth' ? 'Not signed in' : 'Unreachable' }; }
  }
  const c = PROVIDERS[p].cookies;
  for (const name of c.names) {
    const ck = await chrome.cookies.get({ url: c.url, name }).catch(() => null);
    if (ck) return { connected: true, detail: 'Signed in' };
  }
  if (p === 'chatgpt') return { connected: true, detail: 'Guest mode, sign in for more' };
  return { connected: false, detail: 'Not signed in' };
}

// ---------------- messaging ----------------
chrome.runtime.onConnect.addListener(port => {
  if (port.name === 'drv-frame') {
    port.onMessage.addListener(m => {
      if (m.type === 'ready') {
        if (frameWaiter && m.provider === frameWaiter.p) { const w = frameWaiter; frameWaiter = null; w.resolve(port); }
        return;
      }
      if (m.type !== 'drv') return;
      const j = drvJobs.get(m.id);
      if (!j) return;
      if (m.error) { drvJobs.delete(m.id); j.reject(err(m.code || 'driver', m.error)); return; }
      if (typeof m.text === 'string') j.onReplace(m.text);
      if (m.done) { drvJobs.delete(m.id); j.resolve(); }
    });
    return;
  }

  if (port.name !== 'drafty') return;
  const ctrl = new AbortController();
  let closed = false;
  port.onDisconnect.addListener(() => { closed = true; ctrl.abort(); });
  const send = m => { if (!closed) try { port.postMessage(m); } catch {} };

  port.onMessage.addListener(async msg => {
    if (msg.type !== 'generate') return;
    try {
      const p = await getProvider();
      const onReplace = text => send({ type: 'replace', text });
      if (p === 'claude') await claudeGenerate(msg.prompt, { signal: ctrl.signal, cleanup: msg.cleanup !== false, onDelta: text => send({ type: 'delta', text }) });
      else if (p === 'gemini') await geminiGenerate(msg.prompt, { signal: ctrl.signal, onReplace, cleanup: msg.cleanup !== false });
      else await frameGenerate(p, msg.prompt, { signal: ctrl.signal, onReplace });
      send({ type: 'done' });
    } catch (e) {
      if (!ctrl.signal.aborted) send({ type: 'error', code: e.code || 'unknown', message: e.message });
    }
  });
});

async function ensureInjected(tabId) {
  try {
    const r = await chrome.tabs.sendMessage(tabId, { type: 'ping' });
    if (r?.ok) return true;
  } catch {}
  await chrome.scripting.executeScript({ target: { tabId }, files: CONTENT_FILES });
  return true;
}

async function sendToTab(tabId, message) {
  await ensureInjected(tabId);
  await chrome.tabs.sendMessage(tabId, message);
}

const toggleInTab = tabId => sendToTab(tabId, { type: 'toggle' });

chrome.commands.onCommand.addListener(async (command, tab) => {
  if (!tab?.id) return;
  try {
    if (command === 'draft-reply') await toggleInTab(tab.id);
    if (command === 'open-assistant') await sendToTab(tab.id, { type: 'assistant' });
  } catch (e) { console.warn('[Drafty]', e); }
});

function buildMenus() {
  chrome.contextMenus.removeAll(() => {
    chrome.contextMenus.create({ id: 'drafty', title: 'Drafty', contexts: ['selection', 'page'] });
    chrome.contextMenus.create({ id: 'explain', parentId: 'drafty', title: 'Explain selection', contexts: ['selection'] });
    chrome.contextMenus.create({ id: 'summarize', parentId: 'drafty', title: 'Summarize selection', contexts: ['selection'] });
    chrome.contextMenus.create({ id: 'translate', parentId: 'drafty', title: 'Translate selection', contexts: ['selection'] });
    chrome.contextMenus.create({ id: 'reply', parentId: 'drafty', title: 'Draft a reply to this', contexts: ['selection'] });
    chrome.contextMenus.create({ id: 'assistant', parentId: 'drafty', title: 'Open page assistant', contexts: ['selection', 'page'] });
  });
}
chrome.runtime.onInstalled.addListener(async () => {
  installRules();
  buildMenus();
  // Clean up the pinned provider tab older versions used.
  const { ptab } = await chrome.storage.session.get('ptab').catch(() => ({}));
  if (ptab?.id) chrome.tabs.remove(ptab.id).catch(() => {});
});

chrome.contextMenus.onClicked.addListener(async (info, tab) => {
  if (!tab?.id) return;
  try {
    if (info.menuItemId === 'assistant') await sendToTab(tab.id, { type: 'assistant' });
    else await sendToTab(tab.id, { type: 'selection', action: info.menuItemId, text: info.selectionText || '' });
  } catch (e) { console.warn('[Drafty]', e); }
});

chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
  if (msg.type === 'status') {
    (async () => {
      const p = await getProvider();
      const s = await providerStatus(p);
      sendResponse({ ok: s.connected, provider: p, name: PROVIDERS[p].name, detail: s.detail });
    })();
    return true;
  }
  if (msg.type === 'providers') {
    (async () => {
      const out = {};
      for (const p of Object.keys(PROVIDERS)) out[p] = { name: PROVIDERS[p].name, login: PROVIDERS[p].login, ...(await providerStatus(p)) };
      sendResponse(out);
    })();
    return true;
  }
  if (msg.type === 'draft-in-tab' || msg.type === 'assistant-in-tab') {
    (msg.type === 'draft-in-tab' ? toggleInTab(msg.tabId) : sendToTab(msg.tabId, { type: 'assistant' }))
      .then(() => sendResponse({ ok: true }))
      .catch(e => sendResponse({ ok: false, message: e.message }));
    return true;
  }
});
