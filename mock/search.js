/*
 * Free My TV home mock: SEARCH. Loaded after mock.js; uses its globals (S, E, pic, ring, sym, toast, openDetails...).
 * Keys inside search: arrows move, Enter presses, Esc / right-click = Back, Backspace deletes (Back when empty),
 * any letter / digit / space types (so S, M, N... type letters here). The mic chip + Enter simulates voice.
 */
var SQ = '', SRES = null, MIC = false, micT = 0, debT = 0, micN = 0, sBuilt = false;
var SF = { a: 'kb', kr: 0, kc: 0, rr: 0, ri: 0 };
var SLIST = null, SKEYS = [], SRROWS = [], micEl = null, fieldEl = null;
var TRENDING = ['Panchayat', 'Spider-Man', 'RRR', 'Scam 1992', 'Mirzapur', 'Netflix'];
var MIC_PHRASES = ['spider man', 'panchayat', 'rrr'];

/* MARK: - Matching: case/accent-insensitive, word prefix, substring, 1 typo for words >= 4 letters */
function norm(s) { return String(s).toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '').replace(/[^a-z0-9]+/g, ' ').trim(); }
function ed1(a, b) {
  if (a === b) return true;
  var la = a.length, lb = b.length, i = 0;
  if (Math.abs(la - lb) > 1) return false;
  while (i < la && i < lb && a.charAt(i) === b.charAt(i)) i++;
  if (la === lb) return a.slice(i + 1) === b.slice(i + 1);
  return la > lb ? a.slice(i + 1) === b.slice(i) : a.slice(i) === b.slice(i + 1);
}
function wscore(q, words, w) {
  var best = 0, i, x, s;
  for (i = 0; i < words.length; i++) {
    x = words[i];
    if (x.indexOf(q) === 0) s = w[0];
    else if (q.length >= 3 && x.indexOf(q) > 0) s = w[1];
    else if (q.length >= 4 && (ed1(q, x) || ed1(q, x.slice(0, q.length)))) s = w[2];
    else s = 0;
    if (s > best) best = s;
  }
  return best;
}
function scoreHay(q, h) {
  var qw = q.split(' '), tot = 0, i, m;
  if (h.t.indexOf(q) === 0) tot += 1000; else if (q.length >= 3 && h.t.indexOf(q) > 0) tot += 500;
  for (i = 0; i < qw.length; i++) {
    m = Math.max(wscore(qw[i], h.tw, [100, 60, 40]), wscore(qw[i], h.cw, [25, 15, 10]), wscore(qw[i], h.gw, [20, 10, 0]));
    if (!m) return 0;
    tot += m;
  }
  return tot;
}
var INDEX = null;
function buildIndex() {
  var seen = {};
  INDEX = [];
  MOVIES.concat(SHOWS, EXTRA).forEach(function (it) {
    if (seen[it.id]) return; seen[it.id] = 1;
    var t = norm(it.name);
    INDEX.push({ it: it, hay: { t: t, tw: t.split(' '), cw: norm(it.cast.join(' ')).split(' '), gw: norm(it.genres.join(' ')).split(' ') } });
  });
}
function searchData(raw) {
  if (!INDEX) buildIndex();
  var q = norm(raw), titles = [], apps = [];
  INDEX.forEach(function (x) { var s = scoreHay(q, x.hay); if (s) titles.push({ it: x.it, s: s + x.it.rating / 10 }); });
  titles.sort(function (a, b) { return b.s - a.s; });
  APPS.forEach(function (a) { var t = norm(a.name); if (scoreHay(q, { t: t, tw: t.split(' '), cw: [], gw: [] })) apps.push(a); });
  return { q: raw, titles: titles.slice(0, 10).map(function (x) { return x.it; }), apps: apps };
}

/* MARK: - Recent searches (localStorage, max 5) */
function getRecent() { try { return JSON.parse(localStorage.getItem('fmtv_recent') || '[]'); } catch (e) { return []; } }
function saveRecent(q) {
  q = (q || '').replace(/\s+/g, ' ').trim();
  if (q.length < 2) return;
  var l = getRecent().filter(function (x) { return x.toLowerCase() !== q.toLowerCase(); });
  l.unshift(q);
  try { localStorage.setItem('fmtv_recent', JSON.stringify(l.slice(0, 5))); } catch (e) {}
}

/* MARK: - Static parts: field, mic chip, TV keyboard */
function buildSearch() {
  var P = $('search');
  P.innerHTML = '';
  E('div', 'sbg', P);
  fieldEl = E('div', 'sfield glass', P);
  var mg = magIcon(fieldEl); mg.style.cssText = 'left:36px;top:30px';
  E('div', 'sq', fieldEl);
  micEl = E('div', 'smic glass', P);
  var mi = E('div', 'mic', micEl); E('div', 'mb', mi); E('div', 'ma', mi); E('div', 'ms', mi); E('div', 'mt', mi);
  E('div', 'mlab', micEl, 'Mic');
  ring(micEl, 260, 96, 57);
  micEl.onclick = function () { SF.a = 'field'; applyFocus(); searchAct(); };
  SKEYS = [];
  var chars = 'abcdefghijklmnopqrstuvwxyz0123456789'.split(''), i;
  var kb = E('div', 'skb', P);
  for (i = 0; i < 36; i++) addKey(kb, chars[i], chars[i], Math.floor(i / 6), i % 6, 1);
  addKey(kb, 'Space', ' ', 6, 0, 2); addKey(kb, 'Delete', 'del', 6, 2, 2); addKey(kb, 'Clear', 'clear', 6, 4, 2);
  E('div', 'sres', P).id = 'sRes';
  sBuilt = true;
}
function addKey(kb, label, val, r, c, span) {
  var w = span * 84 + (span - 1) * 8;
  var el = E('div', 'skey glass', kb, label, 'left:' + (c * 92) + 'px;top:' + (r * 92) + 'px;width:' + w + 'px;line-height:84px');
  if (span > 1) el.style.fontSize = '30px';
  ring(el, w, 84, 28);
  var k = { el: el, r: r, c: c, s: span, val: val };
  el.onclick = function () { SF.a = 'kb'; SF.kr = r; SF.kc = c; applyFocus(); typeKey(val); };
  SKEYS.push(k);
}
function kbAt(r, c) { for (var i = 0; i < SKEYS.length; i++) if (SKEYS[i].r === r && c >= SKEYS[i].c && c < SKEYS[i].c + SKEYS[i].s) return SKEYS[i]; return null; }

/* MARK: - Query / mic */
function renderField() {
  var q = fieldEl.querySelector('.sq');
  q.innerHTML = '';
  if (MIC) {
    E('span', 'ql', q, 'Listening\u2026');
    var lv = E('span', 'lvl', q);
    for (var i = 0; i < 5; i++) E('i', null, lv, null, '-webkit-animation-delay:' + (i * 0.12) + 's;animation-delay:' + (i * 0.12) + 's');
  } else {
    if (SQ) E('span', 'qt', q, SQ); else E('span', 'ph', q, 'Search movies, shows and apps');
    E('span', 'caret', q);
  }
  micEl.querySelector('.mlab').textContent = MIC ? 'Listening\u2026' : 'Mic';
  stage.classList.toggle('search-duck', MIC);
}
function typeKey(v) {
  if (MIC) cancelMic();
  if (SF.a === 'res') SF.a = 'kb';
  if (v === 'del') SQ = SQ.slice(0, -1); else if (v === 'clear') SQ = ''; else if (SQ.length < 40) SQ += v;
  renderField();
  clearTimeout(debT);
  if (norm(SQ).length >= 2) debT = setTimeout(runSearch, 300); else runSearch();
}
function runSearch() {
  clearTimeout(debT);
  SRES = norm(SQ).length >= 2 ? searchData(SQ) : null;
  renderSearch();
}
function startMic() {
  if (MIC) return;
  MIC = true; clearTimeout(debT); renderField();
  micT = setTimeout(function () {
    MIC = false; SQ = MIC_PHRASES[micN++ % MIC_PHRASES.length];
    SF.a = 'res'; SF.rr = 0; SF.ri = 0;
    renderField(); runSearch(); saveRecent(SQ);
  }, 1500);
}
function cancelMic() { MIC = false; clearTimeout(micT); renderField(); }

/* MARK: - Results */
function chipRows(R, list, y, pick) {
  var x = 0, line = [], rowsOut = [];
  function flush() { if (line.length) { SRROWS.push({ els: line.map(function (c) { return c.el; }), acts: line.map(function (c) { return c.act; }) }); line = []; } }
  list.forEach(function (t) {
    var w = t.length * 17 + 64;
    if (x + w > 1100) { flush(); x = 0; y += 80; }
    var el = E('div', 'schip glass', R, t, 'left:' + x + 'px;top:' + y + 'px;width:' + w + 'px');
    ring(el, w, 64, 40);
    var act = function () { pick(t); };
    el.onclick = function () { SF.a = 'res'; SRROWS.forEach(function (r, rr) { var ii = r.els.indexOf(el); if (ii >= 0) { SF.rr = rr; SF.ri = ii; } }); applyFocus(); act(); };
    line.push({ el: el, act: act });
    x += w + 16;
  });
  flush();
  return y + 80;
}
function pickQuery(t) { SQ = t; renderField(); runSearch(); saveRecent(t); }
function instChips(it) {
  var seen = {}, out = [];
  waysFor(it).forEach(function (g) { g.list.forEach(function (p) { if (!seen[p.name] && p.installed && p.name !== 'Stremio') { seen[p.name] = 1; out.push(p.name); } }); });
  return out.slice(0, 3).concat(['Stremio']);
}
function renderSearch() {
  var R = $('sRes'), q = norm(SQ), y = 0, i;
  R.innerHTML = ''; SRROWS = []; SLIST = null;
  if (q.length < 2) {
    if (q.length === 1) { E('div', 'snote', R, 'Type 2 or more letters to search.', 'top:0px'); y = 60; }
    E('div', 'rlabel', R, 'Recent searches', 'left:0;top:' + y + 'px');
    var rec = getRecent();
    if (rec.length) y = chipRows(R, rec, y + 64, pickQuery) + 24;
    else { E('div', 'snote', R, 'Nothing yet. Your last 5 searches show up here.', 'top:' + (y + 60) + 'px'); y += 150; }
    E('div', 'rlabel', R, 'Trending', 'left:0;top:' + y + 'px');
    chipRows(R, TRENDING, y + 64, pickQuery);
  } else if (SRES && !SRES.titles.length && !SRES.apps.length) {
    E('div', 'sempty', R, 'No results for \u2018' + SQ.trim() + '\u2019.');
    E('div', 'snote', R, 'Try a shorter word.', 'top:90px');
    E('div', 'rlabel', R, 'Trending', 'left:0;top:190px');
    chipRows(R, TRENDING, 254, pickQuery);
  } else if (SRES) {
    var t = SRES.titles, list = [], TH = 340, GAP = 32, ay = 12;
    t.forEach(function (it) { list.push({ it: it }); });
    SRES.apps.forEach(function (a) { var o = { app: a }; if (t.length) list.push(o); else list.unshift(o); });
    var clip = E('div', 'sclip', R), strip = E('div', 'sstrip', clip);
    SLIST = { strip: strip, tiles: [] };
    E('div', 'scount', R, '', 'display:none').id = 'sCount';
    list.forEach(function (x, n) {
      var label = n === 0 ? 'Best match' : x.app ? 'App' : x.it.seasons ? 'Show' : 'Movie', h = x.app ? 120 : TH, el, act;
      el = E('div', 'shero' + (x.app ? ' sapp' : ''), strip, null, 'left:40px;top:' + ay + 'px;height:' + h + 'px');
      if (x.app) {
        el.style.background = x.app.bg;
        E('div', 'shtag', el, label + (n === 0 ? '' : ''), 'top:40px;left:36px');
        E('div', 'shtt clip1', el, x.app.name, 'top:30px;left:260px;height:60px;line-height:60px');
        E('div', 'shmeta clip1', el, 'Open app', 'top:30px;left:700px;width:360px;text-align:right;line-height:60px;height:60px;font-size:30px');
        ring(el, 1104, 120, 36);
        act = function () { toast('Opening ' + x.app.name); };
      } else {
        var it = x.it, bg = E('div', 'shbg img', el); holder(bg, it.bg, it.name);
        E('div', 'shshade', el);
        E('div', 'shtag', el, label + (n === 0 ? ' \u00B7 ' + (it.seasons ? 'Show' : 'Movie') : ''));
        E('div', 'shtt clip1', el, it.name);
        E('div', 'shmeta clip1', el, metaLine(it));
        E('div', 'shov', el, it.overview);
        var bd = E('div', 'sbadges', el);
        instChips(it).forEach(function (nm) { var b = E('span', 'badge inst', bd, nm); sym(b, 'check'); });
        ring(el, 1104, 340, 36);
        act = function () { saveRecent(SQ); openDetails(null, null, it); };
      }
      var row = { els: [el], acts: [act] };
      el.onclick = function () { SF.a = 'res'; SF.rr = SRROWS.indexOf(row); SF.ri = 0; applyFocus(); act(); };
      SRROWS.push(row);
      SLIST.tiles.push({ el: el, y: ay, h: h });
      ay += h + GAP;
    });
  }
  if (SF.a === 'res') { if (!SRROWS.length) SF.a = 'kb'; else { SF.rr = Math.min(SF.rr, SRROWS.length - 1); SF.ri = Math.min(SF.ri, SRROWS[SF.rr].els.length - 1); } }
  applyFocus();
}

/* MARK: - Focus + navigation */
function applyFocus() {
  var all = $('search').querySelectorAll('.focus'), i;
  for (i = 0; i < all.length; i++) all[i].classList.remove('focus');
  if (S.zone !== 'search') return;
  var el = null;
  if (SF.a === 'field') el = micEl;
  else if (SF.a === 'kb') { var k = kbAt(SF.kr, SF.kc); el = k && k.el; }
  else if (SRROWS[SF.rr]) {
    var r = SRROWS[SF.rr]; el = r.els[SF.ri];
  }
  if (el) el.classList.add('focus');
  if (SLIST) listScroll();
}
/* vertical result list: keep the focused tile fully in view (translateY), count "2 / 9", load images near focus only */
function listScroll() {
  var f = SF.a === 'res' ? SF.rr : 0, t = SLIST.tiles, cur = t[f], ty = 0, n = t.length, i;
  var top = cur.y - 12, bot = cur.y + cur.h + 24;
  ty = -Math.max(0, f === 0 ? 0 : Math.min(top, bot - 790));
  if (SLIST.ty != null && SF.a !== 'res') ty = SLIST.ty;
  SLIST.ty = ty;
  SLIST.strip.style.webkitTransform = SLIST.strip.style.transform = 'translateY(' + ty + 'px)';
  for (i = 0; i < n; i++) if (t[i].el.querySelector('.img')) cardPic(t[i].el, Math.abs(i - f) <= 2);
  var c = $('sCount'); c.style.display = SF.a === 'res' ? 'block' : 'none'; c.textContent = (f + 1) + ' / ' + n;
}
function searchMove(dx, dy) {
  var f = SF, k, n;
  if (f.a === 'field') { if (dy > 0) f.a = 'kb'; }
  else if (f.a === 'kb') {
    k = kbAt(f.kr, f.kc);
    if (dx < 0) { n = kbAt(f.kr, k.c - 1); if (n) f.kc = n.c; }
    else if (dx > 0) { n = kbAt(f.kr, k.c + k.s); if (n) f.kc = n.c; else if (SRROWS.length) { f.a = 'res'; f.rr = 0; f.ri = 0; } }
    else if (dy < 0) { if (f.kr === 0) f.a = 'field'; else { f.kr--; f.kc = kbAt(f.kr, k.c).c; } }
    else if (dy > 0 && f.kr < 6) { f.kr++; f.kc = kbAt(f.kr, k.c).c; }
  } else {
    var r = SRROWS[f.rr];
    if (dx < 0) { if (f.ri > 0) f.ri--; else f.a = 'kb'; }
    else if (dx > 0) f.ri = Math.min(r.els.length - 1, f.ri + 1);
    else if (dy < 0) { if (f.rr > 0) { f.rr--; f.ri = Math.min(f.ri, SRROWS[f.rr].els.length - 1); } else f.a = 'field'; }
    else if (dy > 0 && f.rr < SRROWS.length - 1) { f.rr++; f.ri = Math.min(f.ri, SRROWS[f.rr].els.length - 1); }
  }
  applyFocus();
}
function searchAct() {
  var f = SF;
  if (f.a === 'field') startMic();
  else if (f.a === 'kb') typeKey(kbAt(f.kr, f.kc).val);
  else SRROWS[f.rr].acts[f.ri]();
}
function searchKey(e, k) {
  var u = true;
  if (k === 'ArrowLeft') move(-1, 0);
  else if (k === 'ArrowRight') move(1, 0);
  else if (k === 'ArrowUp') move(0, -1);
  else if (k === 'ArrowDown') move(0, 1);
  else if (k === 'Enter') searchAct();
  else if (k === 'Escape') back();
  else if (k === 'Backspace') { if (SQ.length) typeKey('del'); else back(); }
  else if (k.length === 1 && !e.ctrlKey && !e.metaKey && !e.altKey && /[a-z0-9 ]/i.test(k)) typeKey(k.toLowerCase());
  else u = false;
  if (u) e.preventDefault();
}

/* MARK: - Open / close */
function openSearch(q) {
  if (!sBuilt) buildSearch();
  S.zone = 'search';
  stage.classList.add('search-open');
  SQ = q || ''; MIC = false; clearTimeout(micT); clearTimeout(debT);
  SF = { a: 'kb', kr: 0, kc: 0, rr: 0, ri: 0 };
  renderField(); runSearch();
}
function closeSearch() {
  stage.classList.remove('search-open');
  MIC = false; clearTimeout(micT); clearTimeout(debT);
  S.zone = 'top';
  setFocus('top', 0);
}
/* Overview: render the screen with a sample query, clone it, put the live screen back as it was. */
function searchSnapshot(q) {
  if (!sBuilt) buildSearch();
  var sq = SQ, sf = SF, sm = MIC, sz = S.zone;
  S.zone = 'x'; MIC = false; SQ = q; SF = { a: 'kb', kr: 0, kc: 0, rr: 0, ri: 0 };
  renderField(); SRES = searchData(q); renderSearch();
  var c = $('search').cloneNode(true);
  c.removeAttribute('id'); c.style.cssText = 'position:absolute;left:0;top:0;width:1920px;height:1080px;opacity:1';
  SQ = sq; SF = sf; MIC = sm; S.zone = sz;
  renderField(); SRES = norm(SQ).length >= 2 ? searchData(SQ) : null; renderSearch();
  return c;
}

/* MARK: - URL flags: ?search ?search=spider ?search=xyzq ?mic */
(function () {
  if (FLAGS.search) { openSearch(FLAGS.search === '1' ? '' : decodeURIComponent(FLAGS.search)); }
  if (FLAGS.mic) { if (S.zone !== 'search') openSearch(); startMic(); }
})();
