// Hosts ChatGPT or Grok in a hidden frame so Drafty never opens a visible tab.
chrome.runtime.onMessage.addListener((msg, _s, respond) => {
  if (msg.type !== 'offscreen-load') return;
  const f = document.getElementById('f');
  f.src = 'about:blank';
  setTimeout(() => { f.src = msg.url; }, 50);
  respond({ ok: true });
});
