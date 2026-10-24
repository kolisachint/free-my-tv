/*
 * Free My TV: home mock logic. Plain ES5, no frameworks. Keys: arrows, Enter, Backspace/Esc (Back),
 * N = day/night, R = low-RAM. URL flags: ?night ?lowram ?drawer ?details=movie:0|show:2|cont:1 ?focus=show:3
 */
var FLAGS = {};
location.search.replace(/^\?/, '').split('&').forEach(function (p) { if (p) { var a = p.split('='); FLAGS[a[0]] = a[1] || '1'; } });
var LOW = !!FLAGS.lowram, NOCONT = !!FLAGS.nocontinue, FIRST = NOCONT ? 1 : 0;
var $ = function (id) { return document.getElementById(id); };
var stage = $('stage');
var SYM = '../app/assets/symbols/';
var ROW_TOPS = [0, 430, 830, 1230];

function E(tag, cls, parent, text, css) {
  var e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text != null) e.textContent = text;
  if (css) e.style.cssText = css;
  if (parent) parent.appendChild(e);
  return e;
}
/* Symbols are white PNGs in <img>; CSS filter turns them dark where the context is light (masks are blocked on file://). */
function sym(parent, name) {
  var s = document.createElement('img');
  s.className = 'sym';
  s.src = SYM + name + '.png';
  parent.appendChild(s);
  return s;
}
function ring(parent, w, h, r, pad, bw) {
  pad = pad == null ? 9 : pad; bw = bw == null ? 6 : bw;
  return E('div', 'ring', parent, null, 'left:' + (-pad) + 'px;top:' + (-pad) + 'px;width:' + (w + 2 * pad - 2 * bw) + 'px;height:' + (h + 2 * pad - 2 * bw) + 'px;border-radius:' + r + 'px');
}
/* picture with a gradient + title fallback underneath; a broken image just removes itself */
function pic(parent, url, title) {
  var h = hashStr(title) % 360;
  var fb = E('div', 'fb', parent, null, 'background:linear-gradient(135deg,hsl(' + h + ',45%,34%),hsl(' + ((h + 50) % 360) + ',50%,16%))');
  E('div', 'fbt', fb, title);
  var img = document.createElement('img');
  img.onerror = function () { if (img.parentNode) img.parentNode.removeChild(img); };
  img.src = url;
  parent.appendChild(img);
}
function fmtMin(m) { return m >= 60 ? Math.floor(m / 60) + 'h ' + (m % 60) + 'm' : m + ' min'; }
function metaLine(it) {
  var p = [it.year, it.genres.join(', ')];
  if (it.seasons) p.push(it.seasons + (it.seasons > 1 ? ' seasons' : ' season'));
  p.push(fmtMin(it.minutes));
  p.push('IMDb ' + it.rating.toFixed(1));
  return p.join(' \u00B7 ');
}
/* ways to watch, grouped Stream / Rent / Buy, installed first, Stremio last */
function waysFor(it) {
  var all = providersFor(it), groups = [];
  ['stream', 'rent', 'buy'].forEach(function (k) {
    var l = all.filter(function (p) { return p.kind === k; });
    l.sort(function (a, b) { return (b.installed ? 1 : 0) - (a.installed ? 1 : 0); });
    if (l.length) groups.push({ kind: k, list: l });
  });
  return groups;
}
function firstInstalled(it) {
  var g = waysFor(it), i, j;
  for (i = 0; i < g.length; i++) for (j = 0; j < g[i].list.length; j++) if (g[i].list[j].installed) return g[i].list[j].name;
  return 'Stremio';
}

/* MARK: - State */
var S = { zone: 'rows', row: 0, cols: [0, 0, 0, 0], top: 0, dr: 0, dc: 0, night: false, lowram: false, det: null, hero: null, heroSlot: 0 };
var rows = [], topEls = [], drawerEls = [[], []];

/* MARK: - Top bar */
function buildTop() {
  var tb = $('topbar');
  E('div', null, tb).id = 'clockT';
  E('div', null, tb).id = 'clockD';
  var defs = [['live_tv', 'Live TV'], ['hdmi', 'HDMI Inputs'], ['settings', 'Settings']];
  defs.forEach(function (d, i) {
    var b = E('div', 'round glass', tb, null, 'left:' + (1572 + i * 92) + 'px');
    sym(b, d[0]);
    E('div', 'tip', b, d[1]);
    ring(b, 68, 68, 43);
    b.onclick = function () { setFocus('top', i); toast(d[1] + ' (mock)'); };
    topEls.push(b);
  });
  tick(); setInterval(tick, 20000);
}
function tick() {
  var d = new Date(), z = function (n) { return (n < 10 ? '0' : '') + n; };
  $('clockT').textContent = z(d.getHours()) + ':' + z(d.getMinutes());
  var D = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'], M = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  $('clockD').textContent = D[d.getDay()] + ', ' + M[d.getMonth()] + ' ' + d.getDate();
}

/* MARK: - Rows */
function buildRows() {
  var host = $('rows');
  var cont = CONTINUE.map(function (c) { return { item: SHOWS[c.index], cont: c }; });
  var defs = [
    { title: 'Continue in Stremio', kind: 'cont', items: cont, w: 520, h: 293, pitch: 576 },
    { title: 'Movies', kind: 'movie', items: MOVIES.slice(0, LOW ? 8 : 15).map(function (m) { return { item: m }; }), w: LOW ? 300 : 360, h: LOW ? 169 : 203, pitch: LOW ? 332 : 392 },
    { title: 'Shows', kind: 'show', items: SHOWS.slice(0, LOW ? 8 : 15).map(function (m) { return { item: m }; }), w: LOW ? 300 : 360, h: LOW ? 169 : 203, pitch: LOW ? 332 : 392 },
    { title: 'Apps', kind: 'apps', items: APPS.map(function (a) { return { app: a }; }) }
  ];
  defs.forEach(function (d, r) {
    var row = E('div', 'row', host, null, 'top:' + ROW_TOPS[r] + 'px');
    E('div', 'rlabel', row, d.title);
    d.count = E('div', 'rcount', row, '');
    d.el = row;
    if (r < FIRST) row.style.display = 'none';
    d.strip = E('div', 'strip', row);
    d.els = [];
    if (d.kind === 'apps') {
      E('div', 'glass', d.strip, null, 'position:absolute;left:60px;top:72px;width:1800px;height:206px;border-radius:62px');
      d.items.forEach(function (x, i) {
        var t = E('div', 'tile', d.strip, null, 'left:' + (92 + i * 220) + 'px;top:112px;background:' + x.app.bg);
        t.setAttribute('data-dark', x.app.name === 'Apple TV' ? '1' : '0');
        E('div', 'nm', t, x.app.name, x.app.name.length > 9 ? 'font-size:25px' : '');
        ring(t, 196, 126, 30);
        t.onclick = function () { setFocus('rows', r, i); toast('Opens ' + x.app.name + ' (mock)'); };
        d.els.push(t);
      });
    } else {
      d.items.forEach(function (x, i) {
        var c = E('div', 'card', d.strip, null, 'left:' + (96 + i * d.pitch) + 'px;width:' + d.w + 'px');
        var img = E('div', 'img', c, null, 'width:' + d.w + 'px;height:' + d.h + 'px');
        pic(img, LOW ? x.item.bg.replace('/medium/', '/small/') : x.item.bg, x.item.name);
        if (d.kind === 'cont') {
          E('div', 'shade', img);
          E('div', 'left', img, 'S' + x.cont.season + ' E' + x.cont.ep + ' \u00B7 ' + x.cont.minsLeft + ' min left');
          var bar = E('div', 'bar', img); E('i', null, bar, null, 'width:' + x.cont.pct + '%');
          E('div', 'tag', img, 'Stremio');
        }
        var host2 = E('div', 'ringhost', c, null, 'width:' + d.w + 'px;height:' + d.h + 'px');
        ring(host2, d.w, d.h, 28);
        E('div', 't clip1', c, x.item.name, 'top:' + (d.h + 22) + 'px;width:' + d.w + 'px');
        if (d.kind !== 'cont') E('div', 'm clip1', c, x.item.year + ' \u00B7 IMDb ' + x.item.rating.toFixed(1), 'top:' + (d.h + 60) + 'px;width:' + d.w + 'px');
        c.onclick = function () { setFocus('rows', r, i); activate(); };
        d.els.push(c);
      });
    }
    rows.push(d);
  });
}

/* MARK: - Hero (two slots, crossfade by opacity) */
function setHero(it, cont) {
  if (S.hero === it) return;
  S.hero = it;
  var n = 1 - S.heroSlot, old = S.heroSlot;
  S.heroSlot = n;
  var hs = $('hs' + n), ht = $('ht' + n);
  fillHero(hs, ht, it);
  hs.className = 'hslide on'; ht.className = 'htext on';
  $('hs' + old).className = 'hslide'; $('ht' + old).className = 'htext';
}
function fillHero(hs, ht, it) {
  hs.innerHTML = ''; ht.innerHTML = '';
  pic(hs, it.bg, it.name);
  E('div', 'ttl clip1', ht, it.name);
  E('div', 'meta clip1', ht, metaLine(it));
  E('div', 'ov', ht, it.overview);
  var bd = E('div', 'badges', ht);
  E('span', 'wt', bd, 'Watch on');
  var inst = [], seen = {};
  waysFor(it).forEach(function (g) { g.list.forEach(function (p) { if (seen[p.name]) return; seen[p.name] = 1; if (p.installed && p.name !== 'Stremio') inst.push(p.name); }); });
  inst.slice(0, 3).concat(['Stremio']).forEach(function (n) {
    var b = E('span', 'badge inst', bd, n);
    sym(b, 'check');
  });
}

/* MARK: - Focus */
function clearFocus() {
  topEls.forEach(function (e) { e.classList.remove('focus'); });
  rows.forEach(function (d) { d.els.forEach(function (e) { e.classList.remove('focus'); }); d.el.classList.remove('on'); });
}
function setFocus(zone, a, b) {
  clearFocus();
  S.zone = zone;
  if (zone === 'top') { S.top = a; topEls[a].classList.add('focus'); }
  if (zone === 'rows') {
    S.row = a; if (b != null) S.cols[a] = b;
    var d = rows[a], c = S.cols[a];
    d.els[c].classList.add('focus'); d.el.classList.add('on');
    d.count.textContent = (c + 1) + ' / ' + d.items.length;
    if (d.kind !== 'apps') {
      var max = Math.max(0, 96 + d.items.length * d.pitch - 28 - 1920 + 96);
      var tx = -Math.min(max, Math.max(0, c - 1) * d.pitch);
      d.strip.style.webkitTransform = d.strip.style.transform = 'translateX(' + tx + 'px)';
      setHero(d.items[c].item);
    }
  }
  rows.forEach(function (d, i) { d.el.classList.toggle('above', i < S.row); });
  var ty = 24 - ROW_TOPS[S.row];
  $('rows').style.webkitTransform = $('rows').style.transform = 'translateY(' + ty + 'px)';
}

/* MARK: - Drawer */
function buildDrawer() {
  var dr = $('drawer');
  E('div', 'glass', dr, null, 'position:absolute;left:0;top:430px;width:1920px;height:700px;border-radius:62px 62px 0 0').id = 'drawerPanel';
  var inputs = [['live_tv', 'Live TV', 'Antenna', 1], ['hdmi', 'HDMI 1', 'Game console', 1], ['hdmi', 'HDMI 2', 'Set-top box', 1], ['hdmi', 'HDMI 3', 'No signal', 0], ['hdmi', 'HDMI 4', 'PC', 1]];
  var shorts = [['apps', 'All Apps', ''], ['home', 'Home Settings', ''], ['tv_settings', 'TV Settings', '']];
  E('div', 'dlabel', dr, 'Inputs', 'top:470px');
  E('div', 'dlabel', dr, 'Shortcuts', 'top:730px');
  function mk(def, r, i, y, off) {
    var t = E('div', 'dtile glass' + (off ? ' off' : ''), dr, null, 'left:' + (96 + i * 328) + 'px;top:' + y + 'px');
    sym(t, def[0]);
    E('div', 'nm', t, def[1], (def[1].length > 11 ? 'font-size:26px;' : '') + (def[2] ? '' : 'top:55px'));
    if (def[2]) E('div', 'st', t, def[2]);
    ring(t, 300, 150, 34);
    t.onclick = function () { S.dr = r; S.dc = i; setDrawerFocus(); toast(off ? def[1] + ' has no signal' : def[1] + ' (mock)'); };
    drawerEls[r].push(t);
  }
  inputs.forEach(function (d, i) { mk(d, 0, i, 530, !d[3]); });
  shorts.forEach(function (d, i) { mk(d, 1, i, 790, false); });
}
function setDrawerFocus() {
  drawerEls.forEach(function (r) { r.forEach(function (e) { e.classList.remove('focus'); }); });
  drawerEls[S.dr][S.dc].classList.add('focus');
}
function openDrawer() { S.zone = 'drawer'; S.dr = 0; S.dc = 0; stage.classList.add('drawer-open'); clearFocus(); setDrawerFocus(); }
function closeDrawer() { stage.classList.remove('drawer-open'); setFocus('rows', 3); }

/* MARK: - Details sheet */
function openDetails(r, c) {
  var d = rows[r], x = d.items[c], it = x.item;
  S.det = { back: [r, c], idx: 1, els: [], acts: [] };
  var sh = $('sheet'); sh.innerHTML = '';
  var P = E('div', null, sh); P.id = 'sheetPanel';
  var bg = E('div', null, P); bg.id = 'sBg'; pic(bg, it.bg, it.name);
  E('div', null, P).id = 'sFadeL'; E('div', null, P).id = 'sFadeB';
  var po = E('div', null, P); po.id = 'sPoster'; pic(po, it.poster, it.name);
  E('div', null, P, it.name).id = 'sTitle';
  E('div', null, P, metaLine(it)).id = 'sMeta';
  E('div', null, P, it.overview).id = 'sOv';
  E('div', null, P, 'Cast: ' + it.cast.join(', ')).id = 'sCast';
  var app = x.cont ? 'Stremio' : firstInstalled(it);
  var wl = x.cont ? 'Resume S' + x.cont.season + ' E' + x.cont.ep + ' in Stremio' : 'Watch in ' + app;
  var w2 = Math.round(wl.length * 15.5 + 64);
  var b1 = E('div', 'btn sec', P, 'Play trailer', 'left:392px;width:320px'); ring(b1, 320, 78, 48);
  var b2 = E('div', 'btn', P, wl, 'left:732px;width:' + w2 + 'px'); ring(b2, w2, 84, 51);
  E('div', null, P, 'Play trailer opens YouTube.').id = 'sNote';
  E('div', null, P, 'Where-to-watch data: JustWatch via TMDB').id = 'sFoot';
  var W = E('div', 'glass', P); W.id = 'sWays';
  E('div', 'hd', W, 'Ways to watch');
  var det = S.det;
  det.els = [b1, b2]; det.acts = [
    function () { toast('Opens YouTube to play the trailer (mock): ' + it.name); },
    function () { toast('Opens ' + app + (x.cont ? ' at S' + x.cont.season + ' E' + x.cont.ep : '') + ' (mock)'); }
  ];
  b1.onclick = function () { detFocus(0); detAct(); }; b2.onclick = function () { detFocus(1); detAct(); };
  var y = 84, labels = { stream: 'STREAM', rent: 'RENT', buy: 'BUY' };
  function way(p, stremio) {
    var w = E('div', 'way' + (p.installed ? ' inst' : ' dim'), W, null, 'top:' + y + 'px');
    var nm = E('div', 'wn', w, p.name);
    if (p.price) E('small', null, nm, ' ' + p.price);
    if (p.installed) sym(w, 'check');
    E('div', 'wl', w, stremio ? 'via Stremio' : p.installed ? 'Installed' : 'Not installed');
    ring(w, 504, 64, 20, 4, 5);
    var i = det.els.length;
    w.onclick = function () { detFocus(i); detAct(); };
    det.els.push(w);
    det.acts.push(function () { toast(p.installed ? 'Opens ' + p.name + (stremio ? ' (Stremio stream)' : '') + ' (mock)' : p.name + ' is not installed on this TV'); });
    y += 68;
  }
  waysFor(it).forEach(function (g) {
    E('div', 'gh', W, labels[g.kind], 'top:' + y + 'px');
    y += 44;
    g.list.forEach(function (p) { way(p, false); });
  });
  y += 12;
  E('div', 'gh', W, 'ALSO', 'top:' + y + 'px'); y += 44;
  way({ name: 'Stremio', installed: true }, true);
  stage.classList.add('sheet-open');
  S.zone = 'details';
  detFocus(1);
}
function detFocus(i) {
  var d = S.det; d.idx = i;
  d.els.forEach(function (e, k) { if (k === i) e.classList.add('focus'); else e.classList.remove('focus'); });
}
function detAct() { S.det.acts[S.det.idx](); }
function closeDetails() {
  var b = S.det.back; S.det = null;
  stage.classList.remove('sheet-open');
  setFocus('rows', b[0], b[1]);
}

/* MARK: - Input */
function activate() {
  if (S.zone === 'rows') {
    var d = rows[S.row], c = S.cols[S.row];
    if (d.kind === 'apps') toast('Opens ' + d.items[c].app.name + ' (mock)');
    else openDetails(S.row, c);
  } else if (S.zone === 'top') toast(['Live TV', 'HDMI Inputs', 'Settings'][S.top] + ' (mock)');
  else if (S.zone === 'drawer') {
    var t = drawerEls[S.dr][S.dc];
    toast(t.className.indexOf('off') >= 0 ? t.firstChild.nextSibling.textContent + ' has no signal' : t.childNodes[1].textContent + ' (mock)');
  } else if (S.zone === 'details') detAct();
}
function move(dx, dy) {
  var z = S.zone;
  if (z === 'top') {
    if (dx) setFocus('top', Math.max(0, Math.min(2, S.top + dx)));
    else if (dy > 0) setFocus('rows', FIRST);
  } else if (z === 'rows') {
    var d = rows[S.row];
    if (dx) setFocus('rows', S.row, Math.max(0, Math.min(d.items.length - 1, S.cols[S.row] + dx)));
    else if (dy < 0) { if (S.row === FIRST) setFocus('top', S.top); else setFocus('rows', S.row - 1); }
    else if (dy > 0) { if (S.row === 3) openDrawer(); else setFocus('rows', S.row + 1); }
  } else if (z === 'drawer') {
    if (dx) S.dc = Math.max(0, Math.min(drawerEls[S.dr].length - 1, S.dc + dx));
    else if (dy > 0 && S.dr === 0) { S.dr = 1; S.dc = Math.min(S.dc, 2); }
    else if (dy < 0) { if (S.dr === 1) { S.dr = 0; } else { closeDrawer(); return; } }
    setDrawerFocus();
  } else if (z === 'details') {
    var i = S.det.idx, n = S.det.els.length;
    if (i < 2) { if (dx < 0 && i === 1) detFocus(0); else if (dx > 0 && i === 0) detFocus(1); else if (dx > 0 && i === 1) detFocus(2); }
    else { if (dx < 0) detFocus(1); else if (dy) detFocus(Math.max(2, Math.min(n - 1, i + dy))); }
  }
}
function back() {
  if (S.zone === 'details') closeDetails();
  else if (S.zone === 'drawer') closeDrawer();
  else if (S.zone === 'top') setFocus('rows', S.row);
  else toast('Back: already on Home');
}
function setNight(on) { S.night = on; stage.classList.toggle('night', on); document.body.classList.toggle('nightbody', on); }
function setLow(on) { S.lowram = on; stage.classList.toggle('lowram', on); }
/* MARK: - Input: keyboard on window AND document (deduped), wheel, hover, click, right-click */
function onKey(e) {
  if (e.__done) return; e.__done = true;
  var k = e.key, used = true;
  if (OV) {
    if (k === 'o' || k === 'O' || k === 'Escape') leaveOverview();
    else if (k === 'n' || k === 'N') { setNight(!S.night); }
    else used = false;   // arrows, space, PageDown scroll the page natively
    if (used) e.preventDefault();
    return;
  }
  if (k === 'ArrowLeft') move(-1, 0);
  else if (k === 'ArrowRight') move(1, 0);
  else if (k === 'ArrowUp') move(0, -1);
  else if (k === 'ArrowDown') move(0, 1);
  else if (k === 'Enter') activate();
  else if (k === 'Backspace' || k === 'Escape') back();
  else if (k === 'n' || k === 'N') { setNight(!S.night); toast(S.night ? 'Night look' : 'Day look'); }
  else if (k === 'r' || k === 'R') { var q = location.search.replace(/[?&]lowram(=1)?/, '').replace(/^&/, '?'); location.search = LOW ? q : (q ? q + '&lowram' : '?lowram'); }
  else if (k === 'o' || k === 'O') enterOverview();
  else if (k === 'h' || k === 'H') $('hint').classList.toggle('off');
  else used = false;
  if (used) e.preventDefault();
}
window.addEventListener('keydown', onKey, true);
document.addEventListener('keydown', onKey, false);

var lastWheel = 0;
window.addEventListener('wheel', function (e) {
  if (OV) return;
  e.preventDefault();
  var now = Date.now();
  if (now - lastWheel < 220) return;
  var ax = Math.abs(e.deltaX), ay = Math.abs(e.deltaY);
  if (ax < 4 && ay < 4) return;
  lastWheel = now;
  if (e.shiftKey || ax > ay) { var d = ax > ay ? e.deltaX : e.deltaY; move(d > 0 ? 1 : -1, 0); }
  else move(0, e.deltaY > 0 ? 1 : -1);
}, { passive: false });

var mx = -1, my = -1;
window.addEventListener('mousemove', function (e) {
  if (OV || (e.clientX === mx && e.clientY === my)) return;
  mx = e.clientX; my = e.clientY;
  var t = e.target, i, r;
  while (t && t !== document.body) {
    if (S.zone === 'top') { i = topEls.indexOf(t); if (i >= 0) { if (S.top !== i) setFocus('top', i); return; } }
    else if (S.zone === 'rows') for (r = 0; r < rows.length; r++) { i = rows[r].els.indexOf(t); if (i >= 0) { if (S.row !== r || S.cols[r] !== i) setFocus('rows', r, i); return; } }
    else if (S.zone === 'drawer') for (r = 0; r < 2; r++) { i = drawerEls[r].indexOf(t); if (i >= 0) { S.dr = r; S.dc = i; setDrawerFocus(); return; } }
    else if (S.zone === 'details') { i = S.det.els.indexOf(t); if (i >= 0) { detFocus(i); return; } }
    t = t.parentNode;
  }
}, false);
window.addEventListener('contextmenu', function (e) { e.preventDefault(); if (OV) leaveOverview(); else back(); });
function grab() { try { window.focus(); document.body.focus(); } catch (x) {} }
window.addEventListener('click', grab);
window.addEventListener('load', grab);

/* MARK: - Overview: the whole home as one tall page, every part visible, labelled by component */
var OV = false;
function sec(host, name, h, build) {
  var hd = E('div', 'ovh', host, 'Component: ' + name);
  var b = E('div', 'ovb', host, null, 'height:' + h + 'px');
  build(b);
  return b;
}
function stripFocus(el) {
  var f = el.querySelectorAll('.focus'), i;
  for (i = 0; i < f.length; i++) f[i].classList.remove('focus');
  if (el.classList) el.classList.remove('focus');
}
function wrapCards(b, d, label) {
  var lb = E('div', 'rlabel', b, label); lb.style.top = '20px';
  var sc = 200 / d.w, pitch = 224, per = 8;
  d.els.forEach(function (c, i) {
    var k = c.cloneNode(true); stripFocus(k);
    var col = i % per, rw = Math.floor(i / per);
    k.style.left = (96 + col * pitch) + 'px'; k.style.top = (80 + rw * 210) + 'px';
    k.style.width = d.w + 'px';
    k.style.webkitTransformOrigin = k.style.transformOrigin = '0 0';
    k.style.webkitTransform = k.style.transform = 'scale(' + sc + ')';
    b.appendChild(k);
  });
}
function buildOverview() {
  var box = $('ov');
  box.innerHTML = '';
  var wrap = E('div', 'ovwrap', box), y = 0;
  var nm = [rows[1].items.length, rows[2].items.length];
  var heroItem = rows[1].items[0].item;
  sec(wrap, 'Top bar (clock + 3 round buttons)', 150, function (b) { var c = $('topbar').cloneNode(true); c.removeAttribute('id'); stripFocus(c); b.appendChild(c); });
  sec(wrap, 'Hero (follows focus: backdrop, title, meta, overview, installed-app badges)', 600, function (b) {
    var hs = E('div', 'hslide on', b), ht = E('div', 'htext on', b);
    fillHero(hs, ht, heroItem);
  });
  if (!NOCONT) sec(wrap, 'Continue in Stremio row (2, hidden when empty)', 470, function (b) {
    var c = rows[0].el.cloneNode(true); stripFocus(c); c.style.top = '0px';
    c.querySelector('.strip').style.webkitTransform = c.querySelector('.strip').style.transform = 'none'; c.className = 'row'; b.appendChild(c);
  });
  sec(wrap, 'Movies row (' + nm[0] + ' cards, shown wrapped)', 80 + Math.ceil(nm[0] / 8) * 210 + 20, function (b) { wrapCards(b, rows[1], 'Movies'); });
  sec(wrap, 'Shows row (' + nm[1] + ' cards, shown wrapped)', 80 + Math.ceil(nm[1] / 8) * 210 + 20, function (b) { wrapCards(b, rows[2], 'Shows'); });
  sec(wrap, 'App shelf (' + rows[3].items.length + ' installed apps)', 300, function (b) {
    var c = rows[3].el.cloneNode(true); stripFocus(c); c.style.top = '0px'; c.className = 'row'; b.appendChild(c);
  });
  sec(wrap, 'Drawer (Inputs + Shortcuts; opens with Down from the shelf)', 560, function (b) {
    var inner = E('div', 'ovdr', b);
    var c = $('drawer').cloneNode(true); c.removeAttribute('id'); stripFocus(c); c.style.cssText = 'position:absolute;left:0;top:-420px;width:1920px;height:1080px';
    inner.appendChild(c);
  });
  sec(wrap, 'Details sheet: open any card with Enter (not drawn here)', 90, function () {});
  sec(wrap, 'Hint strip + toast: bottom-right keys, bottom-centre pill (not drawn here)', 90, function () {});
  var total = 0, i, kids = wrap.children;
  for (i = 0; i < kids.length; i++) total += kids[i].offsetHeight;
  return total;
}
function fitOverview() {
  if (!OV) return;
  var s = window.innerWidth / 1920, wrap = document.querySelector('.ovwrap');
  wrap.style.webkitTransform = wrap.style.transform = 'scale(' + s + ')';
  $('ov').style.height = Math.ceil(wrap.offsetHeight * s) + 'px';
}
function enterOverview() {
  OV = true;
  document.documentElement.classList.add('ovmode'); document.body.classList.add('ovmode');
  stage.style.display = 'none';
  $('ov').style.display = 'block';
  $('ov').className = S.night ? 'night' : '';
  buildOverview(); fitOverview(); window.scrollTo(0, 0);
}
function leaveOverview() {
  OV = false;
  document.documentElement.classList.remove('ovmode'); document.body.classList.remove('ovmode');
  $('ov').style.display = 'none'; $('ov').innerHTML = '';
  stage.style.display = 'block'; fit();
}
var _setNight = setNight;
setNight = function (on) { _setNight(on); if (OV) $('ov').className = on ? 'night' : ''; };

var toastTimer = 0;
function toast(s) {
  var t = $('toast'); t.textContent = s; t.className = 'on';
  clearTimeout(toastTimer); toastTimer = setTimeout(function () { t.className = ''; }, 2200);
}
function fit() {
  var s = Math.min(window.innerWidth / 1920, window.innerHeight / 1080);
  var x = (window.innerWidth - 1920 * s) / 2, y = (window.innerHeight - 1080 * s) / 2;
  stage.style.webkitTransform = stage.style.transform = 'translate(' + x + 'px,' + y + 'px) scale(' + s + ')';
}
window.addEventListener('resize', function () { fit(); fitOverview(); });

/* MARK: - Start */
buildTop(); buildRows(); buildDrawer(); fit();
(function flags() {
  var f = FLAGS;
  if (f.night) setNight(true);
  if (LOW) setLow(true);
  var kinds = { cont: 0, movie: 1, show: 2, apps: 3 };
  setFocus('rows', FIRST, 0);
  if (f.focus) { var a = f.focus.split(':'); setFocus('rows', kinds[a[0]], +a[1]); }
  if (f.details) { var b = f.details.split(':'); setFocus('rows', kinds[b[0]], +b[1]); openDetails(kinds[b[0]], +b[1]); }
  if (f.drawer) { setFocus('rows', 3, 0); openDrawer(); }
  if (f.overview) enterOverview();
  grab();
})();
