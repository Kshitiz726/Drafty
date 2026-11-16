// Drafty context selection: decides exactly how much of the chat Claude sees.
//
// Model
// -----
// Messages m_1..m_n (oldest → newest). k_i = n - i = distance from the newest message.
//
//   relevance   w_i = 2^(-k_i / h) · ρ^(b_i)
//
//   h   = half-life in messages (default 10): a message 10 back counts half as much as the newest.
//   b_i = number of "session breaks" between m_i and now. A break is a silence longer than G
//         (default 3h, only when timestamps are known). ρ (default 0.35) discounts each break,
//         so yesterday's topic fades fast while today's thread stays fully weighted.
//
// We send a contiguous suffix of length L (contiguous so the conversation still reads naturally),
// choosing the smallest L such that
//
//   1. every unanswered message (everything after my last message) is included          [hard]
//   2. L ≥ L_min (default 8) so tone/style has examples                                  [soft]
//   3. coverage C(L) = Σ_{last L} w_i / Σ_all w_i ≥ θ (default 0.95)                     [stop]
//   4. Σ tokens ≤ B (token budget, default 3000)                                         [cap]
//
// With no breaks, C(L) = 1 - 2^(-L/h), so θ = 0.95 ⇒ L ≈ h · log2(20) ≈ 4.3h ≈ 43 messages.
// Session breaks make C(L) reach θ much sooner, so the window stops at the topic boundary.
// Tokens are estimated at ~4 chars/token for ASCII and ~1.5 chars/token for other scripts
// (Devanagari, CJK, emoji…), plus ~4 tokens of per-line overhead. Any single old message is
// clipped to 1500 chars (middle elided) so one wall of text can't eat the budget.

(() => {
  if (window.DraftyCtx) return;

  const DEFAULTS = {
    budgetTokens: 3000,
    halfLife: 10,
    coverage: 0.95,
    minMessages: 8,
    sessionGapMin: 180,
    sessionDecay: 0.35,
    maxMsgChars: 1500,
  };

  function estimateTokens(text) {
    let ascii = 0, other = 0;
    for (const ch of text) (ch.charCodeAt(0) < 128 ? ascii++ : other++);
    return Math.ceil(ascii / 4 + other / 1.5) + 4;
  }

  function clip(text, max) {
    if (text.length <= max) return text;
    const half = Math.floor((max - 7) / 2);
    return text.slice(0, half) + ' […] ' + text.slice(-half);
  }

  function select(messages, opts = {}) {
    const o = { ...DEFAULTS, ...opts };
    const n = messages.length;
    if (!n) return { selected: [], stats: { total: 0, used: 0, omitted: 0, tokens: 0, coverage: 1, unanswered: 0, sessions: 0 } };

    let lastMe = -1;
    for (let i = n - 1; i >= 0; i--) if (messages[i].who === 'me') { lastMe = i; break; }
    const unanswered = n - 1 - lastMe;

    // Session breaks between i and the end.
    const gapMs = o.sessionGapMin * 60000;
    const breaks = new Array(n);
    let b = 0;
    for (let i = n - 1; i >= 0; i--) {
      breaks[i] = b;
      const t1 = messages[i].ts, t0 = i > 0 ? messages[i - 1].ts : null;
      if (t1 && t0 && t1 - t0 > gapMs) b++;
    }

    const lambda = Math.LN2 / o.halfLife;
    const w = messages.map((_, i) => Math.exp(-lambda * (n - 1 - i)) * Math.pow(o.sessionDecay, breaks[i]));
    const W = w.reduce((s, x) => s + x, 0);
    const minL = Math.min(n, Math.max(o.minMessages, unanswered + 1));

    const out = [];
    let L = 0, cum = 0, tokens = 0;
    for (let i = n - 1; i >= 0; i--) {
      const m = messages[i];
      const text = clip(m.text, i === n - 1 ? o.maxMsgChars * 3 : o.maxMsgChars);
      const t = estimateTokens(text);
      const required = (n - 1 - i) < unanswered && tokens < o.budgetTokens * 2;
      if (!required) {
        if (L >= minL && cum / W >= o.coverage) break;
        if (tokens + t > o.budgetTokens) break;
      }
      out.push({ ...m, text });
      L++; cum += w[i]; tokens += t;
    }
    out.reverse();

    return {
      selected: out,
      stats: { total: n, used: L, omitted: n - L, tokens, coverage: cum / W, unanswered, sessions: b + 1 },
    };
  }

  function format(selected, title) {
    return selected.map(m => {
      const who = m.who === 'me' ? 'Me' : m.who === 'system' ? '[notice]' : (m.name || title || 'Them');
      const time = m.time ? `[${m.time}] ` : '';
      return `${time}${who}: ${m.text.replace(/\n/g, '\n    ')}`;
    }).join('\n');
  }

  // Weighted knapsack (greedy by weight) for non-linear content such as comment threads.
  // items: [{ text, weight, ... }]. Infinity weight = always included.
  // Returns the chosen items in their original order.
  function selectWeighted(items, budgetTokens, maxChars = 600) {
    const ranked = items.map((it, i) => ({ it, i })).sort((a, b) => b.it.weight - a.it.weight);
    const keep = new Set();
    let tokens = 0;
    for (const { it, i } of ranked) {
      const text = clip(it.text, maxChars);
      const t = estimateTokens(text);
      if (it.weight !== Infinity && tokens + t > budgetTokens) continue;
      it.text = text;
      tokens += t;
      keep.add(i);
    }
    return { selected: items.filter((_, i) => keep.has(i)), tokens };
  }

  window.DraftyCtx = { select, selectWeighted, format, estimateTokens, clip, DEFAULTS };
})();
