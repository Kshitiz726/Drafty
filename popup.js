const DEFAULTS = {
  provider: 'claude', autoInsert: false, auto: true, cleanup: true, launcher: true, aboutMe: '', myName: '',
  budgetTokens: 3000, halfLife: 10, assistantBudget: 12000, replyLang: 'Auto', translateTo: 'English',
};
const LANGS = ['English', 'Hindi', 'Hinglish', 'Nepali', 'Bengali', 'Urdu', 'Tamil', 'Telugu', 'Marathi', 'Spanish', 'French', 'German', 'Portuguese', 'Italian', 'Arabic', 'Chinese', 'Japanese', 'Korean', 'Russian', 'Indonesian'];
const ORDER = ['claude', 'gemini'];
const $ = id => document.getElementById(id);
const save = (k, v) => chrome.storage.sync.set({ [k]: v });

let providers = {};
let current = 'claude';

function renderProviders() {
  $('providers').innerHTML = ORDER.map(p => {
    const s = providers[p] || { name: p, detail: 'Checking...' };
    return `<button class="prov ${p === current ? 'on' : ''}" data-p="${p}">
      <span class="dot ${s.connected ? 'ok' : ''}"></span><b>${s.name || p}</b><span>${s.detail || ''}</span></button>`;
  }).join('');
  const s = providers[current];
  const box = $('signin');
  if (s && !s.connected) {
    box.style.display = 'block';
    box.innerHTML = `Sign in to ${s.name} in this browser to use it. <a href="${s.login}" target="_blank">Open ${s.name}</a>`;
  } else if ((current === 'chatgpt' || current === 'grok') && s) {
    box.style.display = 'block';
    box.textContent = `${s.name} runs in a hidden background frame. Keep it signed in.`;
  } else box.style.display = 'none';
}

$('providers').addEventListener('click', e => {
  const b = e.target.closest('.prov');
  if (!b) return;
  current = b.dataset.p;
  save('provider', current);
  renderProviders();
});

chrome.storage.sync.get(DEFAULTS, s => {
  current = ORDER.includes(s.provider) ? s.provider : 'claude';
  for (const k of ['autoInsert', 'auto', 'cleanup', 'launcher']) $(k).checked = s[k];
  for (const k of ['aboutMe', 'myName', 'budgetTokens', 'halfLife', 'assistantBudget']) $(k).value = s[k];
  $('replyLang').innerHTML = ['Auto', ...LANGS].map(l => `<option value="${l}">${l === 'Auto' ? 'Same as chat' : l}</option>`).join('');
  $('translateTo').innerHTML = LANGS.map(l => `<option>${l}</option>`).join('');
  $('replyLang').value = s.replyLang;
  $('translateTo').value = s.translateTo;
  renderOutputs();
  renderProviders();
});

chrome.runtime.sendMessage({ type: 'providers' }, res => { if (res) { providers = res; renderProviders(); } });

function renderOutputs() {
  $('budgetOut').textContent = `${(+$('budgetTokens').value).toLocaleString()} tok`;
  $('halfOut').textContent = `${$('halfLife').value} msgs`;
  $('assistOut').textContent = `${(+$('assistantBudget').value / 1000)}k tok`;
}

for (const k of ['autoInsert', 'auto', 'cleanup', 'launcher']) $(k).addEventListener('change', e => save(k, e.target.checked));
for (const k of ['aboutMe', 'myName']) $(k).addEventListener('input', e => save(k, e.target.value));
for (const k of ['replyLang', 'translateTo']) $(k).addEventListener('change', e => save(k, e.target.value));
for (const k of ['budgetTokens', 'halfLife', 'assistantBudget']) $(k).addEventListener('input', e => { renderOutputs(); save(k, +e.target.value); });

async function runInTab(type) {
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  if (!tab?.id) return;
  chrome.runtime.sendMessage({ type, tabId: tab.id }, res => {
    if (res?.ok) window.close();
    else { $('signin').style.display = 'block'; $('signin').textContent = res?.message || "Drafty can't run on this page."; }
  });
}
$('draftHere').addEventListener('click', () => runInTab('draft-in-tab'));
$('assistHere').addEventListener('click', () => runInTab('assistant-in-tab'));
$('shortcuts').addEventListener('click', e => { e.preventDefault(); chrome.tabs.create({ url: 'chrome://extensions/shortcuts' }); });

chrome.commands?.getAll?.(cmds => {
  const set = (name, id) => { const c = cmds.find(x => x.name === name); if (c) $(id).textContent = c.shortcut || 'No shortcut'; };
  set('draft-reply', 'k1');
  set('open-assistant', 'k2');
});
