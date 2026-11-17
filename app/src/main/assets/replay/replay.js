// Hejje replay video: draws the trip over a 3D landscape built from map and terrain data, with its
// real sky and weather, and encodes it with the phone's video encoder. Data comes from the app (Android.data()); the
// finished MP4 goes back in pieces (Android.chunk). Every frame is a function of time alone.
'use strict';
const W = 1080, H = 1920, FPS = 60;
const SANS = 'Roboto, Inter, "Noto Sans", sans-serif';
const stage = document.getElementById('stage');
const out = document.getElementById('out'), og = out.getContext('2d');
const mk = () => { const c = document.createElement('canvas'); c.width = W; c.height = H; return c; };
const skyC = mk(), fxC = mk(), grC = mk();
const sk = skyC.getContext('2d'), fx = fxC.getContext('2d'), gr = grC.getContext('2d');
const probe = document.createElement('canvas'); probe.width = 27; probe.height = 480; const pg = probe.getContext('2d', { willReadFrequently: true });

const clamp = (x, a = 0, b = 1) => Math.min(b, Math.max(a, x));
const lerp = (a, b, t) => a + (b - a) * t;
const ease = t => (t = clamp(t), t < .5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2);
const smooth = t => (t = clamp(t), t * t * (3 - 2 * t));
const hex = h => [parseInt(h.slice(1, 3), 16), parseInt(h.slice(3, 5), 16), parseInt(h.slice(5, 7), 16)];
const mix = (a, b, t) => a.map((v, i) => Math.round(lerp(v, b[i], t)));
const css = (c, a = 1) => `rgba(${c[0]},${c[1]},${c[2]},${a})`;
const toRad = x => x * Math.PI / 180, toDeg = x => x * 180 / Math.PI;
const sleep = ms => new Promise(r => setTimeout(r, ms));
let seed = 5; const rnd = () => (seed = (seed * 16807) % 2147483647) / 2147483647;

function fit() { const s = Math.min(innerWidth / W, innerHeight / H); stage.style.transform = `translate(${(innerWidth - W * s) / 2}px,${(innerHeight - H * s) / 2}px) scale(${s})`; }
addEventListener('resize', fit); fit();

// ---------------- trip ----------------
let D, route, L, map, stars = [], rain = [], CLOUD;
function at(d) {
  let lo = 0, hi = route.length - 1; d = clamp(d, 0, L);
  while (hi - lo > 1) { const m = (lo + hi) >> 1; if (route[m].d <= d) lo = m; else hi = m; }
  const a = route[lo], b = route[hi], f = b.d > a.d ? (d - a.d) / (b.d - a.d) : 0;
  return { lon: lerp(a.lon, b.lon, f), lat: lerp(a.lat, b.lat, f), t: lerp(a.t, b.t, f), sunEl: lerp(a.sunEl, b.sunEl, f), sunAz: lerp(a.sunAz, b.sunAz, f) };
}
function weather(ts) {
  const w = D.weather;
  if (!w || !w.length) return { known: false, cloud: 0, rain: 0, vis: 30000, code: 0 };
  let i = 0; while (i < w.length - 2 && w[i + 1].t + 1800 <= ts) i++;
  const a = w[i], b = w[Math.min(i + 1, w.length - 1)], f = clamp((ts - (a.t + 1800)) / 3600);
  return { known: true, cloud: lerp(a.cloud, b.cloud, f), rain: lerp(a.rain, b.rain, f), vis: lerp(a.vis, b.vis, f), code: f < .5 ? a.code : b.code };
}

// ---------------- timing: one continuous shot, close where it matters ----------------
// At each moment (the start, a highlight, sunrise or sunset, the arrival) the camera comes right
// down behind the arrow, as close as a navigation view. Between moments it eases back and climbs
// as far as the trip needs, so a short hop stays close all the way and a long road trip still
// fits in about 25 seconds. The ground always slides past at the same gentle speed on screen.
const Z_HL = 17.6, Z_MIN = 9.6, SPEED_PX = 150, OUTRO = 3.4, INTRO = .3;
let vHL, TT, T, moments, OV;
const mppAt = z => 156543 * Math.cos(toRad(route[0].lat)) / Math.pow(2, z);
function buildTiming() {
  vHL = mppAt(Z_HL) * SPEED_PX;
  const merge = Math.max(400, L * .02), groups = [];
  for (const h of D.highlights.slice().sort((a, b) => a.d - b.d)) {
    const g = groups[groups.length - 1];
    if (g && h.d - g.d1 <= merge) { g.items.push(h); g.d1 = h.d; } else groups.push({ d0: h.d, d1: h.d, items: [h] });
  }
  moments = [{ d: 0, hold: .7 }];
  for (const g of groups) moments.push({ d: (g.d0 + g.d1) / 2, span: g.d1 - g.d0, hold: 1.8 + .35 * (g.items.length - 1) + (g.items.some(i => i.kind === 'SUNRISE' || i.kind === 'SUNSET') ? .6 : 0) });
  moments.push({ d: L, hold: .6 });
  // Longer trips may take a little longer, up to about 25 s.
  const goal = clamp(5 + 4.5 * Math.log2(1 + L / 6000), 6.5, 25) - INTRO - OUTRO;
  const lnHL = Math.log(vHL), lnMax = Math.log(mppAt(Z_MIN) * SPEED_PX * 1.4);
  let lo = lnHL, hi = lnMax;
  for (let i = 0; i < 32; i++) { const m = (lo + hi) / 2; if (profile(Math.exp(m)).total > goal) lo = m; else hi = m; }
  TT = profile(Math.exp(hi));
  T = INTRO + TT.total + OUTRO;
}
// Speed along the road for a given top speed: slow at each moment, climbing exponentially away from
// it (so the camera eases out in about a second), capped at vTop.
function profile(vTop) {
  const N = 8000, lv = [], lnHL = Math.log(vHL), lnTop = Math.log(vTop), lambda = vHL * .65;
  for (let i = 0; i <= N; i++) {
    const d = L * i / N; let lnv = lnTop;
    for (const m of moments) { const core = vHL * m.hold / 2 + (m.span || 0) / 2, x = Math.abs(d - m.d); lnv = Math.min(lnv, x <= core ? lnHL : lnHL + (x - core) / lambda); }
    lv.push(Math.exp(lnv) * lerp(.4, 1, smooth(d / (vHL * .6))) * lerp(.4, 1, smooth((L - d) / (vHL * .6))));
  }
  const tc = [0]; for (let i = 1; i <= N; i++) tc.push(tc[i - 1] + (L / N) * 2 / (lv[i] + lv[i - 1]));
  return { N, tc, lv, total: tc[N] };
}
function progress(t) {
  const u = t - INTRO; if (u <= 0) return 0; if (u >= TT.total) return 1;
  const c = TT.tc; let lo = 0, hi = TT.N;
  while (hi - lo > 1) { const m = (lo + hi) >> 1; if (c[m] <= u) lo = m; else hi = m; }
  return (lo + (u - c[lo]) / (c[hi] - c[lo])) / TT.N;
}
function speedAt(d) { return TT.lv[clamp(Math.round(d / L * TT.N), 0, TT.N)]; }
function timeAt(d) { return INTRO + TT.tc[clamp(Math.round(d / L * TT.N), 0, TT.N)]; }

// ---------------- camera: planned for the whole video, then smoothed like a gimbal ----------------
const mercX = lon => lon * 20037508.34 / 180, mercY = lat => Math.log(Math.tan((90 + lat) * Math.PI / 360)) * 6378137;
const unX = x => x * 180 / 20037508.34, unY = y => 360 / Math.PI * Math.atan(Math.exp(y / 6378137)) - 90;
function smoothPt(d, r) { let x = 0, y = 0; for (let j = -4; j <= 4; j++) { const p = at(d + j * r / 4); x += mercX(p.lon); y += mercY(p.lat); } return [x / 9, y / 9]; }
function gauss(arr, sigma) {
  const res = [], n = arr.length, r = Math.ceil(sigma * 3);
  for (let i = 0; i < n; i++) { let s = 0, w = 0; for (let j = -r; j <= r; j++) { const k = clamp(i + j, 0, n - 1), q = Math.exp(-j * j / (2 * sigma * sigma)); s += arr[k] * q; w += q; } res.push(s / w); }
  return res;
}
let CAM;
function buildCamera() {
  const N = Math.round(T * FPS) + 1, raw = { x: [], y: [], b: [], z: [] };
  let prevB = null;
  for (let i = 0; i < N; i++) {
    const d = progress(i / FPS) * L, z = clamp(Math.log2(156543 * Math.cos(toRad(route[0].lat)) * SPEED_PX / speedAt(d)), Z_MIN, Z_HL), mpp = mppAt(z);
    const c = smoothPt(d, clamp(mpp * 70, 15, 6000)), K = clamp(mpp * 300, 60, 15000), a = smoothPt(d - K, K / 2), b = smoothPt(d + K, K / 2);
    let br = toDeg(Math.atan2(b[0] - a[0], b[1] - a[1]));
    if (prevB !== null) { while (br - prevB > 180) br -= 360; while (br - prevB < -180) br += 360; }
    prevB = br; raw.x.push(c[0]); raw.y.push(c[1]); raw.b.push(br); raw.z.push(z);
  }
  const x = gauss(raw.x, .3 * FPS), y = gauss(raw.y, .3 * FPS), b = gauss(raw.b, .65 * FPS), z = gauss(raw.z, .3 * FPS);
  // Close in, the view tilts like a navigation screen; higher up it tilts further to show the sky.
  CAM = x.map((_, i) => ({ lon: unX(x[i]), lat: unY(y[i]), bearing: b[i], zoom: z[i], pitch: lerp(70, 58, smooth((z[i] - 12) / (Z_HL - 12))) }));
}
function camera(t) {
  const k = CAM[clamp(Math.round(t * FPS), 0, CAM.length - 1)], d = progress(t) * L, p = at(d);
  const cam = { center: [k.lon, k.lat], zoom: k.zoom, pitch: k.pitch, bearing: k.bearing, pad: 860 };
  const o = ease((t - (T - OUTRO)) / 2.4);
  if (o > 0) {
    const db = ((OV.bearing - cam.bearing + 540) % 360) - 180;
    cam.center = [lerp(cam.center[0], OV.center[0], o), lerp(cam.center[1], OV.center[1], o)];
    cam.zoom = lerp(cam.zoom, OV.zoom, smooth(o * 1.1)); cam.pitch = lerp(cam.pitch, OV.pitch, o); cam.bearing += db * o; cam.pad = lerp(860, 0, o);
  }
  return { ...cam, d, p, o };
}

// ---------------- the land, built from map data ----------------
// Heights from terrain tiles (real hills), shaded by the real sun; fields, woods, water, towns and
// roads from OpenStreetMap; 3D buildings with sun-cast shadows in towns.
const DEM = 'https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png';
// Daylight colours. Night, dawn and dusk are mixed from these.
const SURF = { land: '#a8c27c', farm: '#b9cd86', grass: '#99c46a', wood: '#5b974b', park: '#88c264', urban: '#e1dacb', water: '#3d95d4',
  sand: '#e6d7a6', curb: '#ece8de', road: '#5e646b', minor: '#8b9096', b1: '#efe6d5', b2: '#d9a98b', b3: '#f3f0ea' };
function style() {
  const name = ['coalesce', ['get', 'name:en'], ['get', 'name_en'], ['get', 'name:latin'], ['get', 'name']];
  const cls = (...c) => ['in', ['get', 'class'], ['literal', c]];
  const w = (z0, w0, z1, w1) => ['interpolate', ['exponential', 1.7], ['zoom'], z0, w0, z1, w1];
  const major = cls('motorway', 'trunk', 'primary', 'secondary');
  const label = (id, c, minzoom) => ({ id, type: 'symbol', source: 'omt', 'source-layer': 'place', minzoom, filter: ['==', ['get', 'class'], c],
    layout: { 'text-field': name, 'text-font': ['Noto Sans Regular'], 'text-size': c === 'city' ? 32 : 26, 'text-letter-spacing': .06, 'text-max-width': 8, 'text-allow-overlap': true, 'text-ignore-placement': true },
    paint: { 'text-color': '#ffffff', 'text-opacity': .9, 'text-halo-color': 'rgba(0,0,0,.45)', 'text-halo-width': 1.8, 'text-halo-blur': 1.2 } });
  return {
    version: 8, glyphs: 'https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf',
    sources: {
      omt: { type: 'vector', url: 'https://tiles.openfreemap.org/planet' },
      dem: { type: 'raster-dem', tiles: [DEM], encoding: 'terrarium', tileSize: 256, maxzoom: 14 },
      shade: { type: 'raster-dem', tiles: [DEM], encoding: 'terrarium', tileSize: 256, maxzoom: 14 },
      world: { type: 'geojson', data: { type: 'Polygon', coordinates: [[[-180, -85], [180, -85], [180, 85], [-180, 85], [-180, -85]]] } },
      all: { type: 'geojson', data: lineFC(null) },
      done: { type: 'geojson', data: lineFC(0) },
    },
    layers: [
      { id: 'bg', type: 'background', paint: { 'background-color': '#cfd6dc' } },
      { id: 'land', type: 'fill', source: 'world', paint: { 'fill-color': SURF.land, 'fill-antialias': false } },
      { id: 'land-tex', type: 'fill', source: 'world', paint: { 'fill-pattern': 'speckle', 'fill-opacity': .5, 'fill-antialias': false } },
      { id: 'farm', type: 'fill', source: 'omt', 'source-layer': 'landcover', filter: cls('farmland'), paint: { 'fill-color': SURF.farm } },
      { id: 'grass', type: 'fill', source: 'omt', 'source-layer': 'landcover', filter: cls('grass', 'scrub'), paint: { 'fill-color': SURF.grass } },
      { id: 'sand', type: 'fill', source: 'omt', 'source-layer': 'landcover', filter: cls('sand', 'bare_rock', 'rock'), paint: { 'fill-color': SURF.sand } },
      { id: 'wood', type: 'fill', source: 'omt', 'source-layer': 'landcover', filter: cls('wood', 'forest'), paint: { 'fill-color': SURF.wood } },
      { id: 'wood-tex', type: 'fill', source: 'omt', 'source-layer': 'landcover', filter: cls('wood', 'forest'), paint: { 'fill-pattern': 'canopy', 'fill-opacity': .85 } },
      { id: 'urban', type: 'fill', source: 'omt', 'source-layer': 'landuse', filter: cls('residential', 'commercial', 'industrial', 'retail', 'suburb', 'neighbourhood'), paint: { 'fill-color': SURF.urban, 'fill-opacity': .92 } },
      { id: 'park', type: 'fill', source: 'omt', 'source-layer': 'park', paint: { 'fill-color': SURF.park } },
      { id: 'water', type: 'fill', source: 'omt', 'source-layer': 'water', paint: { 'fill-color': SURF.water } },
      { id: 'river', type: 'line', source: 'omt', 'source-layer': 'waterway', minzoom: 10, paint: { 'line-color': SURF.water, 'line-width': w(10, .8, 17, 10) } },
      { id: 'hills', type: 'hillshade', source: 'shade', paint: { 'hillshade-illumination-anchor': 'map', 'hillshade-exaggeration': .6, 'hillshade-shadow-color': '#2a2418', 'hillshade-highlight-color': '#fff6e2', 'hillshade-accent-color': '#3c3324' } },
      { id: 'glow', type: 'circle', source: 'omt', 'source-layer': 'place', filter: cls('city', 'town'), layout: { visibility: 'none' },
        paint: { 'circle-color': '#ffb066', 'circle-blur': 1, 'circle-opacity': 0, 'circle-pitch-alignment': 'map', 'circle-pitch-scale': 'map',
          'circle-radius': ['interpolate', ['exponential', 2], ['zoom'], 6, ['match', ['get', 'class'], 'city', 22, 4], 13, ['match', ['get', 'class'], 'city', 2800, 520]] } },
      { id: 'minor', type: 'line', source: 'omt', 'source-layer': 'transportation', minzoom: 12.5, filter: cls('tertiary', 'minor', 'service'), layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: { 'line-color': SURF.minor, 'line-width': w(12.5, .8, 17, 11) } },
      { id: 'curb', type: 'line', source: 'omt', 'source-layer': 'transportation', minzoom: 8, filter: major, layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: { 'line-color': SURF.curb, 'line-width': w(8, 1.4, 17, 34) } },
      { id: 'road', type: 'line', source: 'omt', 'source-layer': 'transportation', minzoom: 8, filter: major, layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: { 'line-color': SURF.road, 'line-width': w(8, 1, 17, 28) } },
      { id: 'lanes', type: 'line', source: 'omt', 'source-layer': 'transportation', minzoom: 14, filter: major, layout: { 'line-join': 'round' },
        paint: { 'line-color': '#ffffff', 'line-opacity': .85, 'line-dasharray': [3, 3], 'line-width': w(14, .4, 17, 1.6) } },
      { id: 'bshadow', type: 'fill', source: 'omt', 'source-layer': 'building', minzoom: 14, paint: { 'fill-color': '#141820', 'fill-opacity': .26, 'fill-translate-anchor': 'map', 'fill-translate': [0, 0] } },
      { id: 'buildings', type: 'fill-extrusion', source: 'omt', 'source-layer': 'building', minzoom: 14,
        paint: { 'fill-extrusion-color': SURF.b1, 'fill-extrusion-height': ['coalesce', ['get', 'render_height'], 6], 'fill-extrusion-base': ['coalesce', ['get', 'render_min_height'], 0], 'fill-extrusion-opacity': 1, 'fill-extrusion-vertical-gradient': true } },
      { id: 'route-all', type: 'line', source: 'all', layout: { 'line-cap': 'round', 'line-join': 'round' }, paint: { 'line-color': '#ffffff', 'line-opacity': .4, 'line-width': w(9, 2, 17, 9) } },
      { id: 'route-glow', type: 'line', source: 'done', layout: { 'line-cap': 'round', 'line-join': 'round' }, paint: { 'line-color': '#ff8a3d', 'line-opacity': .42, 'line-blur': 8, 'line-width': w(9, 9, 17, 30) } },
      { id: 'route', type: 'line', source: 'done', layout: { 'line-cap': 'round', 'line-join': 'round' }, paint: { 'line-color': '#ff7a26', 'line-width': w(9, 3.5, 17, 10) } },
      label('city', 'city', 0), label('town', 'town', 11.5),
    ],
  };
}
// Small tileable textures, drawn once: tree crowns for woods, a fine speckle for open land.
function textures() {
  const make = (size, paint) => { const c = document.createElement('canvas'); c.width = c.height = size; const g = c.getContext('2d'); paint(g, size); return g.getImageData(0, 0, size, size); };
  const canopy = make(96, (g, s) => {
    for (let i = 0; i < 70; i++) {
      const x = rnd() * s, y = rnd() * s, r = 3 + rnd() * 5;
      for (const [ox, oy] of [[0, 0], [s, 0], [-s, 0], [0, s], [0, -s]]) {
        g.fillStyle = `rgba(24,58,22,${.35 + rnd() * .25})`; g.beginPath(); g.arc(x + ox + 1.5, y + oy + 1.5, r, 0, 7); g.fill();
        g.fillStyle = `rgba(${110 + rnd() * 40},${160 + rnd() * 40},${80 + rnd() * 30},${.35 + rnd() * .2})`; g.beginPath(); g.arc(x + ox - 1, y + oy - 1, r * .7, 0, 7); g.fill();
      }
    }
  });
  const speckle = make(64, (g, s) => {
    for (let i = 0; i < 260; i++) { const x = rnd() * s, y = rnd() * s; g.fillStyle = rnd() < .5 ? `rgba(40,70,20,${.08 + rnd() * .1})` : `rgba(255,250,210,${.06 + rnd() * .08})`; g.fillRect(x, y, 1 + rnd() * 2, 1 + rnd() * 2); }
  });
  map.addImage('canopy', canopy); map.addImage('speckle', speckle);
}
function lineFC(upto) {
  const lines = []; let cur = [], piece = route[0].piece;
  for (const r of route) {
    if (upto != null && r.d > upto) break;
    if (r.piece !== piece) { if (cur.length > 1) lines.push(cur); cur = []; piece = r.piece; }
    cur.push([r.lon, r.lat]);
  }
  if (upto != null) { const p = at(upto); cur.push([p.lon, p.lat]); }
  if (cur.length > 1) lines.push(cur);
  return { type: 'Feature', geometry: { type: 'MultiLineString', coordinates: lines }, properties: {} };
}
function noiseCanvas(w, h) {
  const c = document.createElement('canvas'); c.width = w; c.height = h; const g = c.getContext('2d'), img = g.createImageData(w, h);
  const oct = [[6, .5], [12, .25], [24, .15], [48, .1]].map(([n, amp]) => { const v = []; for (let i = 0; i < n * n; i++) v.push(rnd()); return { n, amp, v }; });
  const val = (o, x, y) => { const n = o.n, a = x * n, b = y * n, x0 = Math.floor(a), y0 = Math.floor(b), tx = smooth(a - x0), ty = smooth(b - y0), q = (i, j) => o.v[((j % n + n) % n) * n + ((i % n + n) % n)];
    return lerp(lerp(q(x0, y0), q(x0 + 1, y0), tx), lerp(q(x0, y0 + 1), q(x0 + 1, y0 + 1), tx), ty); };
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) { let v = 0; for (const o of oct) v += val(o, x / w, y / h * .5) * o.amp; const k = (y * w + x) * 4; img.data[k] = img.data[k + 1] = img.data[k + 2] = 255; img.data[k + 3] = Math.round(255 * smooth((v - .38) / .45)); }
  g.putImageData(img, 0, 0); return c;
}
function waitMap() {
  return new Promise(res => {
    const done = () => map.loaded() && map.areTilesLoaded() && (!map.terrain || map.terrain.sourceCache.sourceCache.loaded());
    const f = () => { if (done()) { map.off('render', f); clearTimeout(to); res(); } };
    const to = setTimeout(() => { map.off('render', f); res(); }, 20000);
    map.on('render', f); map.triggerRepaint();
  });
}

// ---------------- light of that hour and that weather ----------------
function look(p, wx) {
  const el = p.sunEl, day = smooth((el - 1) / 10), tw = clamp(1 - Math.abs(el - .5) / 8.5), night = 1 - smooth((el + 9) / 8), cl = wx.cloud / 100;
  return { el, day, tw, night, cl, known: wx.known, golden: tw * (1 - cl * .65), rain: clamp(wx.rain / 1.5) + (wx.code >= 51 && wx.code <= 67 ? .25 : 0), fog: clamp((9000 - wx.vis) / 8000) };
}
// A day colour as it looks at that hour: deep blue night, warm dawn and dusk; cloud mutes it a little.
function tint(dayHex, k) {
  const d = hex(dayHex), g = (d[0] + d[1] + d[2]) / 3;
  const night = mix(mix(d, [g, g, g], .45).map(v => v * .22), [12, 20, 38], .4);
  const dusk = mix(d, [240, 160, 115], .28).map(v => v * .82);
  let c = k.day > 0 ? mix(dusk, d, k.day) : mix(night, dusk, smooth((k.el + 9) / 9));
  const gg = (c[0] + c[1] + c[2]) / 3; c = mix(c, [gg, gg, gg], k.cl * .18).map(v => v * (1 - k.cl * .08));
  return css(c.map(Math.round));
}
function skyColors(k) {
  const top = mix(mix(mix(hex('#02040a'), hex('#1b2850'), 1 - k.night), hex('#4a8bd0'), k.day), mix(hex('#121318'), hex('#a3adb7'), k.day), k.cl * .7);
  const hor = mix(mix(mix(mix(hex('#141c2c'), hex('#f0a37a'), k.tw * (1 - k.cl * .5)), hex('#cfe2f0'), k.day), mix(hex('#1e1d20'), hex('#c4c9cf'), k.day), k.cl * .55), hex('#2a2018'), k.night * k.cl * .5);
  return { top, hor };
}
let lastKey = '';
function applyLook(k, p) {
  const key = [k.day, k.tw, k.night, k.cl, k.el].map(v => v.toFixed(3)).join(); if (key === lastKey) return; lastKey = key;
  const set = (id, prop, v) => map.setPaintProperty(id, prop, v);
  for (const [id, prop, s] of [['land', 'fill-color', 'land'], ['farm', 'fill-color', 'farm'], ['grass', 'fill-color', 'grass'], ['sand', 'fill-color', 'sand'], ['wood', 'fill-color', 'wood'],
    ['urban', 'fill-color', 'urban'], ['park', 'fill-color', 'park'], ['water', 'fill-color', 'water'], ['river', 'line-color', 'water'], ['minor', 'line-color', 'minor'], ['curb', 'line-color', 'curb']]) set(id, prop, tint(SURF[s], k));
  set('road', 'line-color', k.night > .3 ? css(mix(hex('#3a2f26'), hex('#9b6a3a'), .6)) : tint(SURF.road, k));
  set('lanes', 'line-opacity', lerp(.85, .35, k.night));
  set('buildings', 'fill-extrusion-color', ['match', ['%', ['to-number', ['coalesce', ['id'], 0]], 3], 0, tint(SURF.b1, k), 1, tint(SURF.b2, k), tint(SURF.b3, k)]);
  set('wood-tex', 'fill-opacity', lerp(.25, .85, 1 - k.night)); set('land-tex', 'fill-opacity', lerp(.15, .5, 1 - k.night));
  // Towns glow only when the camera is low; from high up they would look like a scatter of dots.
  set('glow', 'circle-opacity', ['interpolate', ['linear'], ['zoom'], 10.5, ['match', ['get', 'class'], 'city', .4 * k.night, 0], 12.5, ['match', ['get', 'class'], 'city', .45 * k.night, .3 * k.night]]);
  map.setLayoutProperty('glow', 'visibility', k.night > .02 ? 'visible' : 'none');
  // The sun: hills shaded from where it really is, buildings casting shadows away from it.
  const up = clamp((k.el + 2) / 12), az = (p.sunAz + 360) % 360, len = clamp(16 / Math.tan(toRad(Math.max(k.el, 4))), 6, 70) * up * (1 - k.cl * .7);
  set('hills', 'hillshade-illumination-direction', az);
  set('hills', 'hillshade-exaggeration', lerp(.3, lerp(.9, .6, smooth((k.el - 5) / 30)), up) * (1 - k.cl * .5));
  set('hills', 'hillshade-shadow-color', css(mix(hex('#0b1020'), mix(hex('#4a2a1a'), hex('#2a2418'), k.day), up)));
  set('hills', 'hillshade-highlight-color', css(mix(hex('#ffc890'), hex('#fff6e2'), k.day), .65 * up));
  set('bshadow', 'fill-translate', [-Math.sin(toRad(az)) * len, Math.cos(toRad(az)) * len]);
  set('bshadow', 'fill-opacity', .3 * up * (1 - k.cl * .6));
  map.setLight({ anchor: 'map', position: [1.5, az, clamp(90 - Math.max(k.el, 4), 10, 86)], color: css(mix(hex('#ffcf9a'), hex('#ffffff'), k.day)), intensity: lerp(.15, .5, up) });
  const sky = skyColors(k);
  set('bg', 'background-color', css(sky.hor));
}
function groundTop() {
  pg.clearRect(0, 0, 27, 480); pg.drawImage(map.getCanvas(), 0, 0, 27, 480);
  const d = pg.getImageData(0, 0, 27, 480).data;
  for (let y = 0; y < 480; y++) { let a = 0; for (let x = 0; x < 27; x++) a += d[(y * 27 + x) * 4 + 3]; if (a / 27 > 200) return y * 4; }
  return 0;
}

// ---------------- brand marks ----------------
const SOLE = new Path2D('M47,78 C40.5,78 37,71.5 38.2,64 C39.2,57.5 42,53.5 42,47 C42,39 46.5,34 53.5,34 C61.5,34 66,40 65,48 C64.2,54.5 61,58.5 59.8,65 C58.5,72.5 54,78 47,78 Z');
const TRAIL = new Path2D('M47.5,70.5 C47.5,64 52.5,61.5 51.5,55.5 C50.6,50 55.5,48 54.5,42.5');
const ICON_TOES = [[44.5, 27.5, 4.6], [54, 25, 3.9], [62, 27.5, 3.4], [68, 33, 3]];
const FEET_TOES = [[44, 27.5, 4.6], [52.8, 23.6, 3.5], [60, 24.2, 3], [66.2, 27, 2.6], [70.6, 31.6, 2.2]];
function appIcon(g, x, y, s) {
  g.save(); g.translate(x, y);
  g.fillStyle = '#1A1C1F'; g.beginPath(); g.roundRect(0, 0, s, s, s * .24); g.fill();
  g.beginPath(); g.roundRect(0, 0, s, s, s * .24); g.clip();
  const k = s / 72; g.translate(-18 * k, -18 * k); g.scale(k, k);
  g.translate(54, 57); g.rotate(toRad(18)); g.scale(.94, .94); g.translate(-54, -54);
  g.fillStyle = '#E8590C'; g.fill(SOLE); for (const [cx, cy, r] of ICON_TOES) { g.beginPath(); g.arc(cx, cy, r, 0, 7); g.fill(); }
  g.strokeStyle = '#1A1C1F'; g.lineWidth = 2.6; g.lineCap = 'round'; g.stroke(TRAIL);
  g.fillStyle = '#1A1C1F'; for (const [cx, cy] of [[47.5, 70.5], [54.5, 42.5]]) { g.beginPath(); g.arc(cx, cy, 2.4, 0, 7); g.fill(); }
  g.restore();
}
function feet(g, x, y, h, color) { // the 👣 mark, bottom-left at (x, y)
  const F = [{ sx: -.5, rot: 4, tx: -44.5, ty: -28.5 }, { sx: .5, rot: -4, tx: -26, ty: -33 }];
  g.save(); g.translate(x, y - h); g.scale(h / 36, h / 36); g.fillStyle = color;
  for (const f of F) { g.save(); g.translate(f.tx + 54.5, f.ty + 49.5); g.rotate(toRad(f.rot)); g.scale(f.sx, .5); g.translate(-54.5, -49.5); g.fill(SOLE); for (const [cx, cy, r] of FEET_TOES) { g.beginPath(); g.arc(cx, cy, r, 0, 7); g.fill(); } g.restore(); }
  g.restore();
}
const LOGO = { x: 44, y: 60, w: 0, h: 72 };
function logo(a) {
  fx.save(); fx.globalAlpha = a; fx.shadowColor = 'rgba(0,0,0,.45)'; fx.shadowBlur = 18;
  appIcon(fx, LOGO.x, LOGO.y, 72);
  fx.font = '700 58px Mallige'; fx.fillStyle = '#ffffff'; fx.textBaseline = 'alphabetic';
  const tx = LOGO.x + 72 + 18, base = LOGO.y + 50, w = fx.measureText(D.brand).width;
  fx.fillText(D.brand, tx, base); fx.shadowBlur = 10; feet(fx, tx + w + 6, base, 30, '#ff8a3d');
  fx.restore();
  LOGO.w = 72 + 18 + w + 50;
}

// ---------------- labels that never overlap ----------------
function box(x, y, w, h) { return { x, y, w, h }; }
function hits(a, b, pad = 10) { return a.x < b.x + b.w + pad && b.x < a.x + a.w + pad && a.y < b.y + b.h + pad && b.y < a.y + a.h + pad; }
function measure(item) {
  fx.font = `600 19px ${SANS}`; fx.letterSpacing = '3px'; const wk = fx.measureText(item.key).width;
  fx.font = `500 36px ${SANS}`; fx.letterSpacing = '0px'; const wv = fx.measureText(item.value).width;
  fx.font = `400 21px ${SANS}`; const ww = item.where ? fx.measureText(item.where).width : 0;
  return { w: Math.max(wk, wv, ww) + 44, h: item.where ? 118 : 88 };
}
// Places each label at the first free spot around its point (near first, then a little away with a
// thin line back). A label that fits nowhere is left out; its dot stays.
function layout(items, obstacles) {
  const placed = [], safe = box(28, 28, W - 56, H - 56);
  for (const it of items) {
    const { w, h } = measure(it), spots = [];
    for (const r of [24, 70, 130]) spots.push([r, -h / 2], [-r - w, -h / 2], [r, -h - r * .6], [-r - w, -h - r * .6], [r, r * .6], [-r - w, r * .6], [-w / 2, -h - r], [-w / 2, r]);
    let chosen = null;
    for (const [dx, dy] of spots) {
      const b = box(it.x + dx, it.y + dy, w, h);
      if (b.x < safe.x || b.y < safe.y || b.x + b.w > safe.x + safe.w || b.y + b.h > safe.y + safe.h) continue;
      if (obstacles.some(o => hits(b, o)) || placed.some(p => hits(b, p.b))) continue;
      chosen = b; break;
    }
    if (chosen) placed.push({ it, b: chosen });
  }
  return placed;
}
function drawLabel({ it, b }) {
  fx.save(); fx.globalAlpha = it.a;
  const cx = clamp(it.x, b.x, b.x + b.w), cy = clamp(it.y, b.y, b.y + b.h);
  if (Math.hypot(cx - it.x, cy - it.y) > 30) { fx.strokeStyle = 'rgba(255,255,255,.55)'; fx.lineWidth = 1.5; fx.beginPath(); fx.moveTo(it.x, it.y); fx.lineTo(cx, cy); fx.stroke(); }
  fx.fillStyle = 'rgba(10,12,16,.55)'; fx.strokeStyle = 'rgba(255,255,255,.14)'; fx.lineWidth = 1.5; fx.beginPath(); fx.roundRect(b.x, b.y, b.w, b.h, 22); fx.fill(); fx.stroke();
  fx.textAlign = 'left'; fx.textBaseline = 'alphabetic';
  fx.font = `600 19px ${SANS}`; fx.letterSpacing = '3px'; fx.fillStyle = 'rgba(255,255,255,.62)'; fx.fillText(it.key, b.x + 22, b.y + 33);
  fx.font = `500 36px ${SANS}`; fx.letterSpacing = '0px'; fx.fillStyle = '#ffffff'; fx.fillText(it.value, b.x + 22, b.y + 72);
  if (it.where) { fx.font = `400 21px ${SANS}`; fx.fillStyle = 'rgba(255,255,255,.6)'; fx.fillText(it.where, b.x + 22, b.y + 102); }
  fx.restore();
}

// ---------------- where a highlight happened: the road and the nearest place ----------------
// Read from the map tiles already loaded for the close-up, so nothing extra is fetched.
const WHERE = new Map();
function latinName(p) { return p['name:en'] || p.name_en || p['name:latin'] || p.name || ''; }
function whereOf(h) {
  if (WHERE.has(h)) return WHERE.get(h);
  const kx = 111320 * Math.cos(toRad(h.lat)), ky = 110540;
  const dist = (lon, lat) => Math.hypot((lon - h.lon) * kx, (lat - h.lat) * ky);
  const segDist = (a, b) => {
    const ax = (a[0] - h.lon) * kx, ay = (a[1] - h.lat) * ky, bx = (b[0] - h.lon) * kx, by = (b[1] - h.lat) * ky;
    const dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy, u = l2 > 0 ? clamp(-(ax * dx + ay * dy) / l2) : 0;
    return Math.hypot(ax + u * dx, ay + u * dy);
  };
  let road = null, best = 70;
  for (const f of map.querySourceFeatures('omt', { sourceLayer: 'transportation_name' })) {
    const g = f.geometry, lines = g.type === 'LineString' ? [g.coordinates] : g.type === 'MultiLineString' ? g.coordinates : [];
    for (const line of lines) for (let i = 1; i < line.length; i++) {
      const d = segDist(line[i - 1], line[i]);
      if (d < best) { best = d; const p = f.properties, n = latinName(p); road = p.ref && n && n.length > 26 ? p.ref : n || p.ref || null; }
    }
  }
  let place = null, near = 9000;
  for (const f of map.querySourceFeatures('omt', { sourceLayer: 'place' })) {
    const p = f.properties; if (!['city', 'town', 'village', 'suburb', 'hamlet', 'neighbourhood', 'quarter'].includes(p.class) || f.geometry.type !== 'Point') continue;
    const d = dist(...f.geometry.coordinates), n = latinName(p);
    if (n && d < near) { near = d; place = n; }
  }
  const text = [road, place && (near < 800 ? place : `${D.near} ${place}`)].filter(Boolean).join(' · ');
  const out = text || null;
  if (out) WHERE.set(h, out);
  return out;
}
function totalBox(a) {
  if (a <= 0) return null;
  fx.font = `600 22px ${SANS}`; fx.letterSpacing = '4px'; const wk = fx.measureText(D.total.key.toUpperCase()).width;
  fx.font = `500 76px ${SANS}`; fx.letterSpacing = '0px'; const wv = fx.measureText(D.total.value).width;
  const w = Math.max(wk, wv) + 80, b = box((W - w) / 2, H - 300, w, 170);
  fx.save(); fx.globalAlpha = a; fx.fillStyle = 'rgba(10,12,16,.55)'; fx.strokeStyle = 'rgba(255,255,255,.14)'; fx.lineWidth = 1.5;
  fx.beginPath(); fx.roundRect(b.x, b.y, b.w, b.h, 34); fx.fill(); fx.stroke(); fx.textAlign = 'center';
  fx.font = `600 22px ${SANS}`; fx.letterSpacing = '4px'; fx.fillStyle = 'rgba(255,255,255,.62)'; fx.fillText(D.total.key.toUpperCase(), W / 2, b.y + 52);
  fx.font = `500 76px ${SANS}`; fx.letterSpacing = '0px'; fx.fillStyle = '#ffffff'; fx.fillText(D.total.value, W / 2, b.y + 132);
  fx.restore(); return b;
}
function highlights(t, c, arrowAt) {
  const end = clamp((t - (T - OUTRO + 1.5)) / .7), items = [], dots = [];
  for (const h of D.highlights) {
    if (c.d < h.d - 1 && end <= 0) continue;
    const q = map.project([h.lon, h.lat]); if (q.x < -50 || q.x > W + 50 || q.y < 0 || q.y > H + 50) continue;
    const tb = timeAt(h.d), since = t - tb, pop = clamp(since / .35);
    // One dot for points that sit on top of each other.
    if (!dots.some(o => Math.hypot(o.x - q.x, o.y - q.y) < 22)) dots.push({ x: q.x, y: q.y, a: Math.max(pop, end), r: 8 + (1 - pop) * 10 });
    const live = clamp((since + .25) / .35) * (1 - clamp((since - 2.1) / .4));
    const a = Math.max(live * (1 - clamp(c.o * 2)), end);
    // The road and the nearest place, only in the close-up: the end card stays short.
    const where = live > .01 && c.o <= 0 ? whereOf(h) : null;
    if (a > .01) items.push({ x: q.x, y: q.y, a, key: h.key.toUpperCase(), value: h.value, where: end > 0 ? null : where });
  }
  for (const d of dots) { fx.save(); fx.globalAlpha = d.a; fx.fillStyle = '#ffffff'; fx.strokeStyle = '#ff7a26'; fx.lineWidth = 4; fx.shadowColor = 'rgba(0,0,0,.5)'; fx.shadowBlur = 8; fx.beginPath(); fx.arc(d.x, d.y, d.r, 0, 7); fx.fill(); fx.stroke(); fx.restore(); }
  const obstacles = [box(LOGO.x, LOGO.y, LOGO.w, LOGO.h), ...dots.map(d => box(d.x - 12, d.y - 12, 24, 24))];
  if (arrowAt) obstacles.push(box(arrowAt.x - 50, arrowAt.y - 50, 100, 100));
  if (end > 0) obstacles.push(box(W / 2 - 330, H - 320, 660, 300));
  layout(items, obstacles).forEach(drawLabel);
}

// ---------------- one frame ----------------
async function frame(t, warm) {
  const c = camera(t), p = c.p, wx = weather(p.t), k = look(p, wx);
  map.jumpTo({ center: c.center, zoom: c.zoom, pitch: c.pitch, bearing: c.bearing, padding: { top: c.pad, bottom: 0, left: 0, right: 0 } });
  if (!warm) {
    applyLook(k, p); map.getSource('done').setData(lineFC(c.d));
    // Names would crowd the far horizon when the camera is tilted: fade them there.
    map.setPaintProperty('city', 'text-opacity', .9 * (1 - clamp((c.pitch - 30) / 20) * clamp((13.5 - c.zoom) / .8)));
  }
  await waitMap();
  if (!warm) { draw(t, c, p, k); compose(t); }
}
function draw(t, c, p, k) {
  fx.clearRect(0, 0, W, H); gr.clearRect(0, 0, W, H); sk.clearRect(0, 0, W, H);
  const gy = groundTop(), hy = gy > 4 ? gy : 0, { top, hor } = skyColors(k);
  let g;
  // The sky sits behind the land: hills and towns cut into it.
  g = sk.createLinearGradient(0, 0, 0, Math.max(hy, 200) + 120); g.addColorStop(0, css(top)); g.addColorStop(.6, css(mix(top, hor, .55))); g.addColorStop(1, css(hor));
  sk.fillStyle = g; sk.fillRect(0, 0, W, H);
  if (hy > 4) {
    const rel = ((p.sunAz - c.bearing + 540) % 360) - 180, fovx = map.transform.fov * W / H, sx = W / 2 + rel / fovx * W, sy = hy - p.sunEl / map.transform.fov * H * .9;
    const glow = clamp(1 - Math.abs(p.sunEl - 2) / 12) * (1 - k.cl * .5);
    if (glow > .01) {
      g = sk.createRadialGradient(sx, sy, 0, sx, sy, W * 1.1);
      g.addColorStop(0, `rgba(255,214,160,${.85 * glow})`); g.addColorStop(.2, `rgba(255,168,110,${.45 * glow})`); g.addColorStop(.55, `rgba(232,122,122,${.12 * glow})`); g.addColorStop(1, 'rgba(232,122,122,0)');
      sk.save(); sk.globalCompositeOperation = 'screen'; sk.fillStyle = g; sk.fillRect(0, 0, W, hy + 60); sk.restore();
    }
    if (p.sunEl > -.9) { sk.save(); sk.globalAlpha = clamp((p.sunEl + .9) / 1.5) * (1 - k.cl * .7); sk.fillStyle = '#fff6e4'; sk.shadowColor = '#ffd08a'; sk.shadowBlur = 60; sk.beginPath(); sk.arc(sx, sy, 22, 0, 7); sk.fill(); sk.restore(); }
    const sa = k.known ? k.night * clamp(1 - k.cl / .6) : 0;       // stars only when we know the night was clear
    if (sa > .01) for (const [x, y, r, ph, b] of stars) { const yy = y * hy; sk.fillStyle = `rgba(255,250,240,${sa * (.3 + .55 * b) * (.75 + .25 * Math.sin(t * 2.2 + ph)) * clamp((hy - yy) / 80)})`; sk.beginPath(); sk.arc(x, yy, r, 0, 7); sk.fill(); }
    if (k.cl > .05) {
      const tint2 = mix(mix(mix(hex('#3b3330'), hex('#f2a985'), k.golden), hex('#f6f8fa'), k.day), hex('#8c939b'), k.cl * k.day * .5);
      const tmp = document.createElement('canvas'); tmp.width = W; tmp.height = Math.max(1, Math.round(hy)); const tg = tmp.getContext('2d');
      const off = ((c.bearing / 360) * 2048 + t * 16) % 1024;
      for (let i = -1; i < 2; i++) tg.drawImage(CLOUD, i * 1024 - off + W / 2, hy * .05, 1024, hy * .9);
      tg.globalCompositeOperation = 'source-in'; tg.fillStyle = css(tint2); tg.fillRect(0, 0, W, hy);
      sk.save(); sk.globalAlpha = clamp(k.cl * 1.1) * .75; sk.drawImage(tmp, 0, 0); sk.restore();
    }
    // A light haze where land meets sky gives depth without washing the colours out.
    g = fx.createLinearGradient(0, hy - 30, 0, hy + 300); g.addColorStop(0, css(hor, .8)); g.addColorStop(.3, css(hor, .35)); g.addColorStop(1, css(hor, 0));
    fx.fillStyle = g; fx.fillRect(0, hy - 30, W, 330);
  }
  if (k.fog > .02) { fx.fillStyle = css(hor, k.fog * .3); fx.fillRect(0, 0, W, H); }
  if (k.rain > .03 && c.o < 1) {
    fx.save(); fx.strokeStyle = `rgba(215,225,240,${clamp(k.rain) * .32 * (1 - c.o)})`; fx.lineWidth = 1.3; fx.lineCap = 'round';
    for (const [x, y, s] of rain) { const yy = (y + t * 2600 * s) % H, xx = (x - t * 260 * s + W * 2) % W; fx.beginPath(); fx.moveTo(xx, yy); fx.lineTo(xx - 6 * s, yy + 36 * s); fx.stroke(); }
    fx.restore();
  }
  const gTop = mix(mix(hex('#2d4f9a'), hex('#ff9a5e'), k.golden), hex('#9cc0ff'), k.day * (1 - k.golden)), gBot = mix(mix(hex('#1b2d5a'), hex('#ffc27a'), k.golden), hex('#ffe0b4'), k.day * (1 - k.golden));
  g = gr.createLinearGradient(0, 0, 0, H); g.addColorStop(0, css(gTop, .38)); g.addColorStop(1, css(gBot, .32)); gr.fillStyle = g; gr.fillRect(0, 0, W, H);
  g = fx.createRadialGradient(W / 2, H * .56, H * .34, W / 2, H * .56, H * .85); g.addColorStop(0, 'rgba(0,0,0,0)'); g.addColorStop(1, `rgba(0,0,0,${lerp(.45, .22, k.day)})`);
  fx.fillStyle = g; fx.fillRect(0, 0, W, H);
  const arrowAlpha = 1 - smooth((t - (T - OUTRO + 1.1)) / .9);
  const arrowAt = c.o < 1 && arrowAlpha > 0 ? arrow(t, c, arrowAlpha) : null;
  logo(clamp((t - .2) / .6));
  totalBox(clamp((t - (T - OUTRO + 1.3)) / .7));
  highlights(t, c, arrowAt);
  if (t > T - 2.4) { fx.save(); fx.globalAlpha = clamp((t - (T - 2.4)) / .8) * .55; fx.font = `400 17px ${SANS}`; fx.fillStyle = '#ffffff'; fx.textAlign = 'center'; fx.fillText(D.credit, W / 2, H - 44); fx.restore(); }
  const fi = clamp(t / .5); if (fi < 1) { fx.fillStyle = `rgba(0,0,0,${1 - fi})`; fx.fillRect(0, 0, W, H); }
}

function arrow(t, c, alpha) {
  const p = c.p, q = map.project([p.lon, p.lat]), mpp = 156543 * Math.cos(toRad(p.lat)) / Math.pow(2, c.zoom), dd = clamp(mpp * 45, 25, 2500);
  const a = smoothPt(c.d - dd, dd / 2), b = smoothPt(c.d + dd, dd / 2), qa = map.project([unX(a[0]), unY(a[1])]), qb = map.project([unX(b[0]), unY(b[1])]);
  const ang = Math.atan2(qb.y - qa.y, qb.x - qa.x) + Math.PI / 2;
  fx.save(); fx.globalAlpha = alpha * clamp((t - .3) / .5); fx.translate(q.x, q.y);
  const g = fx.createRadialGradient(0, 0, 0, 0, 0, 80); g.addColorStop(0, 'rgba(255,170,100,.5)'); g.addColorStop(1, 'rgba(255,170,100,0)');
  fx.fillStyle = g; fx.beginPath(); fx.arc(0, 0, 80, 0, 7); fx.fill();
  fx.rotate(ang); fx.scale(1, .82);
  fx.shadowColor = 'rgba(0,0,0,.5)'; fx.shadowBlur = 16; fx.shadowOffsetY = 5;
  fx.beginPath(); fx.moveTo(0, -30); fx.lineTo(22, 24); fx.lineTo(0, 13); fx.lineTo(-22, 24); fx.closePath();
  fx.fillStyle = '#ffffff'; fx.fill(); fx.shadowColor = 'transparent'; fx.lineWidth = 3; fx.strokeStyle = '#ff9a4d'; fx.lineJoin = 'round'; fx.stroke();
  fx.restore();
  return { x: q.x, y: q.y };
}
function compose(t) {
  og.globalCompositeOperation = 'source-over'; og.fillStyle = '#000'; og.fillRect(0, 0, W, H);
  og.drawImage(skyC, 0, 0);
  og.filter = 'saturate(1.12) contrast(1.04)'; og.drawImage(map.getCanvas(), 0, 0, W, H); og.filter = 'none';
  og.globalCompositeOperation = 'soft-light'; og.drawImage(grC, 0, 0);
  og.globalCompositeOperation = 'source-over'; og.drawImage(fxC, 0, 0);
}


// ---------------- encoding ----------------
async function pickCodec() {
  if (typeof VideoEncoder === 'undefined') return null;
  for (const [codec, kind] of [['avc1.64002a', 'avc'], ['avc1.640028', 'avc'], ['avc1.4d002a', 'avc'], ['avc1.4d0028', 'avc'], ['avc1.42e02a', 'avc'], ['vp09.00.41.08', 'vp9']]) {
    const config = { codec, width: W, height: H, bitrate: 20_000_000, framerate: FPS, latencyMode: 'quality' };
    if (kind === 'avc') config.avc = { format: 'avc' };
    try { const s = await VideoEncoder.isConfigSupported(config); if (s.supported) return { config, kind }; } catch (e) { /* try the next */ }
  }
  return null;
}
function toBase64(bytes) {
  let s = ''; for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
  return btoa(s);
}
// Progress goes to the app a few times a second, not every frame.
let lastReport = 0;
function report(x, stage) { const now = performance.now(); if (now - lastReport > 150 || x >= 1) { lastReport = now; Android.progress(x, stage); } }
async function run() {
  try {
    D = JSON.parse(Android.data());
    route = D.route.map(r => ({ lon: r[0], lat: r[1], d: r[2], t: r[3], piece: r[4], sunEl: r[5], sunAz: r[6] }));
    L = route[route.length - 1].d;
    if (!(L > 50)) throw new Error('short');
    const codec = await pickCodec(); if (!codec) { Android.fail('unsupported'); return; }
    await document.fonts.load('700 58px Mallige');
    for (let i = 0; i < 360; i++) stars.push([rnd() * W, rnd(), rnd() * 1.5 + .4, rnd() * 6.28, rnd()]);
    for (let i = 0; i < 280; i++) rain.push([rnd() * W, rnd() * H, rnd() * .6 + .7, rnd()]);
    CLOUD = noiseCanvas(512, 192);
    buildTiming();
    map = new maplibregl.Map({ container: 'map', style: style(), center: [route[0].lon, route[0].lat], zoom: Z_HL, pitch: 60, maxPitch: 80, interactive: false, attributionControl: false,
      preserveDrawingBuffer: true, fadeDuration: 0, pixelRatio: 1, maxTileCacheSize: 1500 });
    await new Promise((res, rej) => { map.on('load', res); map.on('error', e => { if (!map.loaded()) rej(e.error || new Error('map')); }); });
    textures(); map.setTerrain({ source: 'dem', exaggeration: 1.5 });
    let w = 180, e = -180, s = 90, n = -90; for (const r of route) { w = Math.min(w, r.lon); e = Math.max(e, r.lon); s = Math.min(s, r.lat); n = Math.max(n, r.lat); }
    const ov = map.cameraForBounds([[w, s], [e, n]], { padding: { top: 260, bottom: 500, left: 130, right: 130 }, bearing: 0 });
    OV = { center: [ov.center.lng, ov.center.lat], zoom: Math.min(ov.zoom, 15), pitch: 0, bearing: 0 };
    buildCamera();
    if (D.stills) { for (const t of (D.stills === 'highlights' ? D.highlights.map(h => timeAt(h.d) + .3) : D.stills)) { await frame(t, false); await window.__shot(t); } Android.done('stills', T); return; }   // tests only
    const frames = Math.min(Math.round(T * FPS), D.limitFrames || Infinity);   // limitFrames: tests only
    // Fetch the map along the way first, so drawing never waits mid-shot.
    for (let f = 0; f < frames; f += 12) { await frame(f / FPS, true); report(.2 * f / frames, 'map'); }
    const muxer = new Mp4Muxer.Muxer({
      target: new Mp4Muxer.StreamTarget({ onData: (data, position) => Android.chunk(toBase64(data), position), chunked: true, chunkSize: 2 ** 20 }),
      video: { codec: codec.kind, width: W, height: H, frameRate: FPS }, fastStart: false, firstTimestampBehavior: 'offset',
    });
    let failed = null;
    const enc = new VideoEncoder({ output: (chunk, meta) => muxer.addVideoChunk(chunk, meta), error: err => { failed = err; } });
    enc.configure(codec.config);
    for (let f = 0; f < frames; f++) {
      if (failed) throw failed;
      await frame(f / FPS, false);
      const vf = new VideoFrame(out, { timestamp: Math.round(f * 1e6 / FPS), duration: Math.round(1e6 / FPS) });
      enc.encode(vf, { keyFrame: f % (FPS * 2) === 0 }); vf.close();
      while (enc.encodeQueueSize > 2) await sleep(4);
      report(.2 + .8 * (f + 1) / frames, 'video');
    }
    await enc.flush(); if (failed) throw failed;
    muxer.finalize();
    Android.done(codec.kind, T);
  } catch (err) {
    Android.fail(String(err && err.message || err));
  }
}
run();
