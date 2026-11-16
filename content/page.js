// Drafty page reader: extracts the post/article and its comments on any site.
// Site-specific readers for Reddit, YouTube, X, Facebook, LinkedIn, Instagram and Hacker News,
// with a structural fallback for everything else.

(() => {
  if (window.DraftyPage) return;
  const A = window.DraftyAdapters;
  const { textOf } = A;
  const H = location.hostname.replace(/^(www|old|m|new)\./, '');

  function num(s) {
    if (s == null) return 0;
    const m = String(s).replace(/,/g, '').match(/(-?\d+(?:\.\d+)?)\s*([kKmM])?/);
    if (!m) return 0;
    return Math.round(+m[1] * (m[2] ? (/k/i.test(m[2]) ? 1e3 : 1e6) : 1));
  }

  function mainText(root, max = 15000) {
    if (!root) return '';
    const parts = [];
    let len = 0;
    for (const el of root.querySelectorAll('h1, h2, h3, p, li, blockquote, pre')) {
      if (el.closest('nav, footer, aside, header, form, [role="navigation"], [role="complementary"], [data-drafty-c], drafty-root, drafty-assistant')) continue;
      if (el.querySelector('p, li, h1, h2, h3, blockquote, pre')) continue;
      const t = textOf(el);
      if (t.length < 2) continue;
      parts.push(t);
      len += t.length;
      if (len > max) break;
    }
    return parts.join('\n');
  }

  const SITES = [
    {
      name: 'Reddit',
      count: 'shreddit-comment, .thing.comment',
      test: () => /(^|\.)reddit\.com$/.test(location.hostname),
      post() {
        const p = document.querySelector('shreddit-post');
        if (p) {
          const body = p.querySelector('[slot="text-body"]') || document.querySelector('[id$="-post-rtjson-content"]');
          return { el: p, author: p.getAttribute('author') || '', title: p.getAttribute('post-title') || textOf(p.querySelector('[slot="title"]')), text: textOf(body), score: num(p.getAttribute('score')) };
        }
        const o = document.querySelector('.thing.link');
        if (o) return { el: o, author: o.getAttribute('data-author') || '', title: textOf(o.querySelector('a.title')), text: textOf(o.querySelector('.usertext-body .md')) };
        return null;
      },
      comments() {
        const n = [...document.querySelectorAll('shreddit-comment')];
        if (n.length) {
          return n.map(c => ({
            el: c,
            author: c.getAttribute('author') || '',
            depth: +c.getAttribute('depth') || 0,
            score: num(c.getAttribute('score')),
            text: textOf(c.querySelector(':scope > [slot="comment"]') || c.querySelector('[slot="comment"]')),
          }));
        }
        return [...document.querySelectorAll('.thing.comment')].map(c => {
          let depth = 0;
          for (let p = c.parentElement?.closest('.thing.comment'); p; p = p.parentElement?.closest('.thing.comment')) depth++;
          return { el: c, author: c.getAttribute('data-author') || '', depth, score: num(c.querySelector('.score.unvoted')?.getAttribute('title')), text: textOf(c.querySelector(':scope > .entry .md')) };
        });
      },
    },
    {
      name: 'YouTube',
      count: 'ytd-comment-thread-renderer',
      test: () => H.endsWith('youtube.com'),
      threadSel: 'ytd-comment-thread-renderer',
      post() {
        const t = document.querySelector('ytd-watch-metadata h1, h1.ytd-watch-metadata, #title h1');
        if (!t) return null;
        return {
          el: t,
          title: textOf(t),
          author: textOf(document.querySelector('ytd-watch-metadata ytd-channel-name a, #owner ytd-channel-name a')),
          text: textOf(document.querySelector('#description-inline-expander, ytd-text-inline-expander')).slice(0, 4000),
        };
      },
      comments() {
        let n = [...document.querySelectorAll('ytd-comment-view-model, ytd-comment-renderer')];
        n = n.filter(c => !n.some(o => o !== c && o.contains(c)));
        return n.map(c => ({
          el: c,
          author: textOf(c.querySelector('#author-text')),
          depth: c.closest('ytd-comment-replies-renderer') ? 1 : 0,
          score: num(textOf(c.querySelector('#vote-count-middle'))),
          text: textOf(c.querySelector('#content-text')),
        }));
      },
    },
    {
      name: 'X',
      count: () => location.pathname.includes('/status/') ? document.querySelectorAll('article[data-testid="tweet"]').length - 1 : 0,
      test: () => H === 'x.com' || H === 'twitter.com',
      threadSel: '[role="dialog"]',
      post() {
        if (!/\/status\//.test(location.pathname)) return null;
        const a = document.querySelector('[data-testid="primaryColumn"] article[data-testid="tweet"]');
        return a ? { el: a, author: textOf(a.querySelector('[data-testid="User-Name"]')).split('\n')[0], title: '', text: textOf(a.querySelector('[data-testid="tweetText"]')) } : null;
      },
      comments() {
        let arts = [...document.querySelectorAll('article[data-testid="tweet"]')];
        if (/\/status\//.test(location.pathname)) arts = arts.filter(a => a !== this.post()?.el);
        return arts.map(a => ({
          el: a,
          author: textOf(a.querySelector('[data-testid="User-Name"]')).split('\n')[0],
          depth: 0,
          score: num(a.querySelector('[data-testid="like"]')?.getAttribute('aria-label')),
          text: textOf(a.querySelector('[data-testid="tweetText"]')),
        }));
      },
    },
    {
      name: 'Facebook',
      count: '[role="article"][aria-label*="omment" i], [role="article"][aria-label*="eply" i]',
      test: () => H.endsWith('facebook.com'),
      post() {
        const scope = document.querySelector('[role="dialog"]') || document;
        const m = scope.querySelector('[data-ad-preview="message"], [data-ad-comet-preview="message"]');
        return m ? { el: m, author: '', title: '', text: textOf(m) } : null;
      },
      comments() {
        return [...document.querySelectorAll('[role="article"][aria-label]')]
          .filter(a => /comment|reply|टिप्पणी|commentaire|comentario/i.test(a.getAttribute('aria-label')))
          .map(a => {
            const label = a.getAttribute('aria-label');
            const author = (label.match(/by\s+(.+?)(?:\s+\d|\s+(?:a|an)\s|$)/i) || [])[1] || '';
            const text = [...a.querySelectorAll('div[dir="auto"]')]
              .filter(d => d.closest('[role="article"]') === a)
              .map(d => textOf(d)).filter(Boolean).join('\n');
            return { el: a, author, depth: /reply/i.test(label) ? 1 : 0, score: 0, text };
          });
      },
    },
    {
      name: 'LinkedIn',
      count: 'article.comments-comment-entity, .comments-comment-item',
      test: () => H.endsWith('linkedin.com'),
      post() {
        const d = document.querySelector('.feed-shared-update-v2__description, .update-components-text');
        return d ? { el: d, author: textOf(document.querySelector('.update-components-actor__name')).split('\n')[0], title: '', text: textOf(d) } : null;
      },
      comments() {
        return [...document.querySelectorAll('article.comments-comment-entity, .comments-comment-item, article.comments-comment-item')].map(c => ({
          el: c,
          author: textOf(c.querySelector('.comments-comment-meta__description-title, .comments-post-meta__name-text')).split('\n')[0],
          depth: c.closest('.comments-replies-list, .comments-comment-item__nested-items') ? 1 : 0,
          score: 0,
          text: textOf(c.querySelector('.comments-comment-item__main-content, .comments-comment-item-content-body, .update-components-text')),
        }));
      },
    },
    {
      name: 'Hacker News',
      count: 'tr.athing.comtr',
      test: () => H === 'news.ycombinator.com',
      post() {
        const t = document.querySelector('.titleline');
        return t ? { el: t, title: textOf(t), author: textOf(document.querySelector('.fatitem .hnuser')), text: textOf(document.querySelector('.fatitem .toptext')) } : null;
      },
      comments() {
        return [...document.querySelectorAll('tr.athing.comtr')].map(c => ({
          el: c,
          author: textOf(c.querySelector('.hnuser')),
          depth: Math.round((+c.querySelector('td.ind')?.getAttribute('indent') || 0)),
          score: 0,
          text: textOf(c.querySelector('.commtext')),
        }));
      },
    },
  ];

  const GENERIC = {
    name: H,
    post() {
      const root = document.querySelector('main article, article') || document.querySelector('main, [role="main"]') || document.body;
      const h1 = document.querySelector('h1');
      return { el: root, title: h1 ? textOf(h1) : document.title, author: textOf(document.querySelector('[rel="author"], [itemprop="author"]')).split('\n')[0], text: mainText(root) };
    },
    comments() {
      let c = [...document.querySelectorAll('[class*="comment" i], [id^="comment" i], [data-testid*="comment" i], [itemtype*="Comment"], [role="article"]')].slice(0, 1500);
      const textCache = new Map();
      const T = el => { if (!textCache.has(el)) textCache.set(el, (el.innerText || '').trim()); return textCache.get(el); };
      c = c.filter(el => el.getClientRects().length && !el.closest('drafty-root, drafty-assistant, nav, header, footer') && T(el).length >= 15 && T(el).length < 6000);
      c = c.filter(el => !c.some(o => o !== el && el.contains(o) && T(o).length >= 0.5 * T(el).length));
      const seen = new Set();
      return c.filter(el => { const t = T(el); if (seen.has(t)) return false; seen.add(t); return true; }).slice(0, 600).map(el => {
        const a = el.querySelector('[class*="author" i], [rel="author"], [itemprop="author"], a[href*="/user"], a[href*="/u/"]');
        const author = a ? textOf(a).split('\n')[0].slice(0, 40) : '';
        let depth = 0;
        for (let p = el.parentElement; p; p = p.parentElement) if (c.includes(p)) depth++;
        return { el, author, depth, score: 0, text: textOf(el) };
      });
    },
  };

  const site = () => SITES.find(s => s.test()) || GENERIC;
  const siteName = () => site().name;

  // Full page snapshot. Comments get stable ids C1..Cn and are tagged in the DOM for jumping.
  function extract() {
    const s = site();
    let post = null, comments = [];
    try { post = s.post(); } catch (e) { console.warn('[Drafty]', e); }
    try { comments = s.comments(); } catch (e) { console.warn('[Drafty]', e); }
    if (s !== GENERIC && !comments.length) { try { comments = GENERIC.comments(); } catch {} }
    comments = comments.filter(c => c.text && c.text.length > 1).slice(0, 600);
    document.querySelectorAll('[data-drafty-c]').forEach(e => e.removeAttribute('data-drafty-c'));
    comments.forEach((c, i) => { c.id = 'C' + (i + 1); try { c.el.setAttribute('data-drafty-c', c.id); } catch {} });
    if (!post || (!post.text && !post.title)) {
      try { post = { ...GENERIC.post(), ...(post || {}) }; if (!post.text) post.text = GENERIC.post().text; } catch {}
    }
    return { site: s.name, url: location.href, title: document.title, post, comments, threadSel: s.threadSel };
  }

  // Context for replying from a specific text box (comment mode).
  function hash(str) {
    let h = 5381;
    for (let i = 0; i < str.length; i++) h = ((h << 5) + h + str.charCodeAt(i)) | 0;
    return (h >>> 0).toString(36);
  }

  function replyContext(composer, settings, full = false) {
    const C = window.DraftyCtx;
    const page = extract();
    const cs = page.comments;

    let ti = -1;
    cs.forEach((c, i) => { if (A.composedContains(c.el, composer)) ti = i; });
    if (ti < 0 && page.threadSel) {
      const thread = A.composedClosest(composer, page.threadSel);
      if (thread) {
        cs.forEach((c, i) => {
          if (thread.contains(c.el) && (c.el.compareDocumentPosition(composer) & Node.DOCUMENT_POSITION_FOLLOWING)) ti = i;
        });
      }
    }
    const target = ti >= 0 ? cs[ti] : null;

    // Parent chain (by depth) is always included.
    const parents = [];
    if (target && target.depth > 0) {
      let d = target.depth;
      for (let i = ti - 1; i >= 0 && d > 0; i--) if (cs[i].depth < d) { parents.unshift(cs[i]); d = cs[i].depth; }
    }

    // Other comments: w = 2^(-distance/8) * (1 + 0.15 * log2(1 + upvotes)), greedy within the token budget.
    const anchor = ti >= 0 ? ti : 0;
    const skip = new Set([target, ...parents]);
    const items = cs.filter(c => !skip.has(c)).map(c => ({
      ...c,
      weight: Math.pow(2, -Math.abs(cs.indexOf(c) - anchor) / 8) * (1 + 0.15 * Math.log2(1 + Math.max(0, c.score || 0))),
    }));
    const budget = full ? (+settings.assistantBudget || 12000) : (+settings.budgetTokens || 3000);
    const postText = C.clip(page.post?.text || '', 3000);
    const fixed = C.estimateTokens(postText) + (target ? C.estimateTokens(C.clip(target.text, 2000)) : 0) +
      parents.reduce((s, p) => s + C.estimateTokens(C.clip(p.text, 1000)), 0);
    const { selected, tokens } = C.selectWeighted(items, Math.max(300, budget - fixed), 600);

    return {
      kind: 'comment',
      platform: page.site,
      title: page.post?.title || page.title,
      post: page.post ? { title: page.post.title || '', author: page.post.author || '', text: postText } : null,
      target: target ? { author: target.author, text: C.clip(target.text, 2000) } : null,
      parents: parents.map(p => ({ author: p.author, text: C.clip(p.text, 1000) })),
      others: selected.map(c => ({ author: c.author, text: c.text, score: c.score })),
      stats: { used: selected.length, total: cs.length, tokens: tokens + fixed },
      sig: 'comment#' + hash(location.href + '|' + (page.post?.text || '').slice(0, 2000) + '|' + (target ? target.text : '') + '|' + cs.length + '|' + (cs[cs.length - 1]?.text || '')),
      statsText: `${target ? 'Replying to ' + (target.author || 'comment') : 'Replying to post'} · ${selected.length + parents.length + (target ? 1 : 0)}/${cs.length} comments · ~${tokens + fixed} tok`,
    };
  }

  // Lightweight count for the launcher (no DOM tagging).
  function quickCount() {
    const s = site();
    try {
      if (s === GENERIC) return document.querySelectorAll('[class*="comment" i] p, [role="article"]').length > 6 ? 6 : 0;
      return typeof s.count === 'function' ? s.count() : document.querySelectorAll(s.count).length;
    } catch { return 0; }
  }

  function jumpTo(id) {
    const el = document.querySelector(`[data-drafty-c="${id}"]`);
    if (!el) return false;
    el.scrollIntoView({ behavior: 'smooth', block: 'center' });
    const prev = el.style.outline, prevOff = el.style.outlineOffset, prevR = el.style.borderRadius;
    el.style.outline = '2px solid #D97757';
    el.style.outlineOffset = '4px';
    el.style.borderRadius = '8px';
    setTimeout(() => { el.style.outline = prev; el.style.outlineOffset = prevOff; el.style.borderRadius = prevR; }, 2600);
    return true;
  }

  window.DraftyPage = { extract, replyContext, siteName, quickCount, jumpTo, mainText };
})();
