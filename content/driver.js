// Runs only inside Drafty's hidden ChatGPT or Grok frame. Types the prompt, sends it,
// and streams the answer back to the background worker.

(() => {
  let hidden = false;
  try {
    const anc = location.ancestorOrigins;
    hidden = window.top !== window && !!anc && anc.length > 0 && anc[anc.length - 1].startsWith('chrome-extension://');
  } catch {}
  if (!hidden || window.__draftyDriver) return;
  window.__draftyDriver = true;

  const provider = location.hostname.includes('chatgpt') ? 'chatgpt' : 'grok';
  const S = {
    chatgpt: {
      composer: ['#prompt-textarea', 'div.ProseMirror[contenteditable="true"]', 'textarea[name="prompt-textarea"]', 'textarea[name="prompt"]', '#mobile-composer-prompt'],
      send: ['button[data-testid="send-button"]', 'button#composer-submit-button', 'button[aria-label="Send message"]', 'button[aria-label*="Send" i]'],
      stop: ['button[data-testid="stop-button"]', 'button[aria-label*="Stop" i]'],
      answer: ['[data-message-author-role="assistant"]', '[data-message-role="assistant"] [data-assistant-markdown-root]', '[data-message-role="assistant"]'],
    },
    grok: {
      composer: ['textarea[aria-label]', 'div.ProseMirror[contenteditable="true"]', 'div[contenteditable="true"]', 'textarea'],
      send: ['button[type="submit"]', 'button[aria-label*="Submit" i]', 'button[aria-label*="Send" i]'],
      stop: ['button[aria-label*="Stop" i]'],
      answer: ['.message-bubble', '[class*="message-bubble"]'],
    },
  }[provider];

  const sleep = ms => new Promise(r => setTimeout(r, ms));
  const q = sels => { for (const s of sels) { const e = document.querySelector(s); if (e) return e; } return null; };
  const qa = sels => { for (const s of sels) { const l = document.querySelectorAll(s); if (l.length) return [...l]; } return []; };
  const esc = s => s.replace(/[&<>]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c]));

  const port = chrome.runtime.connect({ name: 'drv-frame' });
  const cancelled = new Set();

  async function waitComposer() {
    for (let i = 0; i < 60; i++) { const c = q(S.composer); if (c) return c; await sleep(500); }
    return null;
  }

  async function run(id, prompt) {
    const send = m => { try { port.postMessage({ type: 'drv', id, ...m }); } catch {} };
    const composer = await waitComposer();
    if (!composer) {
      const loginish = /log ?in|sign ?in/i.test(document.body?.innerText?.slice(0, 2000) || '');
      return send({ error: loginish ? `Sign in to ${provider === 'grok' ? 'Grok' : 'ChatGPT'} in a normal tab first.` : 'Could not open the chat. Try again.', code: loginish ? 'auth' : 'driver' });
    }
    const before = qa(S.answer).length;
    composer.focus();
    if (composer.tagName === 'TEXTAREA') {
      Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set.call(composer, prompt);
      composer.dispatchEvent(new Event('input', { bubbles: true }));
    } else {
      const sel = getSelection(); const r = document.createRange();
      r.selectNodeContents(composer); sel.removeAllRanges(); sel.addRange(r);
      const dt = new DataTransfer(); dt.setData('text/plain', prompt);
      composer.dispatchEvent(new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true }));
      await sleep(250);
      if (!composer.innerText.trim()) { document.execCommand('insertText', false, prompt); await sleep(200); }
      if (!composer.innerText.trim()) {
        composer.innerHTML = prompt.split('\n').map(l => '<p>' + (esc(l) || '<br>') + '</p>').join('');
        composer.dispatchEvent(new InputEvent('input', { bubbles: true }));
      }
    }
    let btn = null;
    for (let i = 0; i < 25; i++) { btn = q(S.send); if (btn && !btn.disabled && btn.getAttribute('aria-disabled') !== 'true') break; await sleep(200); }
    if (btn && !btn.disabled) btn.click();
    else composer.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, bubbles: true }));

    const t0 = Date.now();
    let last = '', changedAt = Date.now();
    for (;;) {
      if (cancelled.has(id)) return;
      await sleep(350);
      const list = qa(S.answer);
      const node = list.length > before ? list[list.length - 1] : null;
      let text = node ? (node.innerText || '').trim() : '';
      if (text && text.slice(0, 200) === prompt.trim().slice(0, 200)) text = '';
      if (text && text !== last) { last = text; changedAt = Date.now(); send({ text }); }
      const quiet = Date.now() - changedAt;
      const streaming = !!q(S.stop);
      if (last && ((!streaming && quiet > 1500) || quiet > 9000)) return send({ done: true });
      if (!last && Date.now() - t0 > 60000) return send({ error: 'No answer came back. You may be rate limited, try again shortly.', code: 'driver' });
      if (Date.now() - t0 > 180000) return send({ done: true });
    }
  }

  port.onMessage.addListener(m => {
    if (m.type === 'run') run(m.id, m.prompt).catch(e => port.postMessage({ type: 'drv', id: m.id, error: String(e?.message || e), code: 'driver' }));
    if (m.type === 'cancel') cancelled.add(m.id);
  });
  port.postMessage({ type: 'ready', provider, url: location.href });
})();
