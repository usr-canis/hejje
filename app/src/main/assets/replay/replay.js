// Hejje replay video: draws the trip over a 3D landscape built from map and terrain data, with its
// real sky and weather, and encodes it with the phone's video encoder. Data comes from the app (Android.data()); the
// finished MP4 goes back in pieces (Android.chunk). Every frame is a function of time alone.
'use strict';
const W = 1080, H = 1920, FPS = 30;
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

// ---------------- timing and height ----------------
// Calm everywhere: at highlights, sunrise or sunset, rain and the ends the road slides past at a
// calm pace at navigation height; between them it eases (over a few seconds) to at most twice that
// pace, and on long trips the camera also climbs gradually so the trip fits in a minute, coming
// back down gradually for the next moment. The arrow never stops moving.
const Z_NAV = 16.6, Z_MIN = 9.8, PITCH = 60, S0 = 140, S1 = 280, OUTRO = 3.4, INTRO = .3, START = 1.8, MAX_T = 60;
let T, PLAN, OV, MOMENTS = [], skyZones = [];
const mppAt = z => 156543 * Math.cos(toRad(route[0].lat)) / Math.pow(2, z);
const rampTime = dz => 1.8 + .45 * Math.max(0, dz);    // gentle: about 2 s for speed alone, longer for a climb
// A zoom from za to zb at the calm pace: the mean metres per pixel along an eased change.
const meanMpp = (za, zb) => { let s = 0; for (let i = 0; i < 24; i++) s += mppAt(lerp(za, zb, smooth((i + .5) / 24))); return s / 24; };
function buildTiming() {
  const vNav = mppAt(Z_NAV) * S0, m = [];
  m.push({ d0: 0, d1: vNav * 1.3, secs: 2.6, z: Z_NAV, shape: 'in' });
  for (const h of D.highlights) m.push({ d0: h.d - vNav * 1, d1: h.d + vNav * 1.2, secs: 2.2, z: Z_NAV });
  // Dawn and dusk: the stretch with the sun near the horizon plays over about 5.5 s, looking up at the sky.
  skyZones = []; let z0 = null;
  for (let i = 0; i < route.length; i++) {
    const tw = route[i].sunEl > -8 && route[i].sunEl < 5;
    if (tw && z0 === null) z0 = route[i].d;
    if ((!tw || i === route.length - 1) && z0 !== null) { if (route[i].d - z0 > 50) skyZones.push({ d0: z0, d1: route[i].d }); z0 = null; }
  }
  const wet = d => { const w = weather(at(d).t); return w.known && (w.rain >= .1 || (w.code >= 51 && w.code <= 67)); };
  for (let i = 1; i <= 200; i++) { const a = L * (i - 1) / 200, b = L * i / 200; if (a > vNav * 4 && !wet(a) && wet(b)) skyZones.push({ d0: b - vNav, d1: b + vNav * 1.5 }); }
  for (const s of skyZones) { const secs = s.d1 - s.d0 > vNav * 3 ? 5 : 2.4; m.push({ d0: s.d0, d1: s.d1, secs, z: clamp(Math.log2(156543 * Math.cos(toRad(route[0].lat)) * S0 * secs / Math.max(1, s.d1 - s.d0)), Z_MIN + 1, Z_NAV) }); }
  m.push({ d0: Math.max(0, L - vNav), d1: L, secs: 2, z: Z_NAV, shape: 'out' });
  // Clip to the route and merge moments that touch.
  m.sort((a, b) => a.d0 - b.d0);
  MOMENTS = [];
  for (const x of m) {
    x.d0 = clamp(x.d0, 0, L); x.d1 = clamp(x.d1, 0, L);
    const last = MOMENTS[MOMENTS.length - 1];
    if (last && x.d0 <= last.d1 + vNav * .5) { if (x.shape) last.shape = x.shape; last.d1 = Math.max(last.d1, x.d1); last.secs = Math.max(last.secs + x.secs * .6, (last.d1 - last.d0) / mppAt(Math.min(last.z, x.z)) / S1); last.z = Math.min(last.z, x.z); }
    else MOMENTS.push({ ...x });
  }
  // The lowest the camera may need to fly: as low as possible, high enough for the minute.
  // Longer trips may run longer, up to a minute; a short drive stays short.
  const goal = clamp(12 + 7 * Math.log2(1 + L / 5000), 12, MAX_T) - INTRO - OUTRO;
  let zc = Z_NAV;
  if (totalTime(Z_NAV) > goal) { let lo = Z_MIN, hi = Z_NAV; for (let i = 0; i < 26; i++) { const mid = (lo + hi) / 2; if (totalTime(mid) > goal) hi = mid; else lo = mid; } zc = lo; }
  PLAN = buildPlan(zc);
  T = INTRO + PLAN.end + OUTRO;
}
function legPlan(G, zA, zB, zc) {
  // Climb from zA towards zc and come down to zB, easing speed up to at most S1; p = how far.
  const lowZ = Math.min(zA, zB);
  const tryP = p => {
    const zp = lowZ - p * (lowZ - Math.min(lowZ, zc)), sp = S0 + p * (S1 - S0), ru = rampTime(zA - zp), rd = rampTime(zB - zp);
    const ramp = (za, r) => { let s = 0; for (let i = 0; i < 24; i++) { const b = smooth((i + .5) / 24); s += (S0 + (sp - S0) * b) * mppAt(za + (zp - za) * b) * r / 24; } return s; };
    return { zp, sp, ru, rd, dist: ramp(zA, ru) + ramp(zB, rd) };
  };
  const full = tryP(1);
  if (full.dist <= G) return { ...full, cruise: (G - full.dist) / (full.sp * mppAt(full.zp)), G };
  if (tryP(0).dist > G) return { short: true, G, secs: G / (S0 * meanMpp(zA, zB)), zA, zB };
  let lo = 0, hi = 1; for (let i = 0; i < 24; i++) { const mid = (lo + hi) / 2; if (tryP(mid).dist > G) hi = mid; else lo = mid; }
  return { ...tryP(lo), cruise: 0, G };
}
const legTime = l => l.short ? l.secs : l.ru + l.rd + l.cruise;
function totalTime(zc) {
  let t = 0;
  for (let i = 0; i < MOMENTS.length; i++) {
    t += MOMENTS[i].secs;
    if (i + 1 < MOMENTS.length) t += legTime(legPlan(Math.max(0, MOMENTS[i + 1].d0 - MOMENTS[i].d1), MOMENTS[i].z, MOMENTS[i + 1].z, zc));
  }
  return t;
}
// The whole flight sampled every 1/120 s: time, distance along the road, camera height.
function buildPlan(zc) {
  const dt = 1 / 120, ts = [], ds = [], zs = [];
  let t = 0, d = 0;
  const push = (z, dd) => { ts.push(t); ds.push(d); zs.push(z); t += dt; d += dd; };
  for (let i = 0; i < MOMENTS.length; i++) {
    const mo = MOMENTS[i], n = Math.max(1, Math.round(mo.secs / dt)), step = (mo.d1 - mo.d0) / n;
    // The arrow sets off gently and comes gently to a stop; elsewhere it keeps the calm pace.
    const shape = u => mo.shape === 'in' ? u * u : mo.shape === 'out' ? 1 - (1 - u) * (1 - u) : u;
    for (let k = 0; k < n; k++) { d = mo.d0 + (mo.d1 - mo.d0) * shape(k / n); push(mo.z, 0); }
    d = mo.d1;
    if (i + 1 === MOMENTS.length) break;
    const next = MOMENTS[i + 1], G = Math.max(0, next.d0 - mo.d1), l = legPlan(G, mo.z, next.z, zc), start = ds.length;
    if (l.short) { const k = Math.max(1, Math.round(l.secs / dt)); for (let j = 0; j < k; j++) { const z = lerp(mo.z, next.z, smooth(j / k)); push(z, S0 * mppAt(z) * dt); } }
    else {
      const phase = (za, r, up) => { const k = Math.max(1, Math.round(r / dt)); for (let j = 0; j < k; j++) { const b = up ? smooth(j / k) : 1 - smooth(j / k), z = za + (l.zp - za) * b; push(z, (S0 + (l.sp - S0) * b) * mppAt(z) * dt); } };
      phase(mo.z, l.ru, true);
      const kc = Math.round(l.cruise / dt); for (let j = 0; j < kc; j++) push(l.zp, l.sp * mppAt(l.zp) * dt);
      // Coming down: z goes from zp back to next.z.
      const k = Math.max(1, Math.round(l.rd / dt));
      for (let j = 0; j < k; j++) { const b = 1 - smooth(j / k), z = next.z + (l.zp - next.z) * b; push(z, (S0 + (l.sp - S0) * b) * mppAt(z) * dt); }
    }
    // Land exactly where the next moment starts.
    const got = d - mo.d1; if (got > 0) for (let j = start; j < ds.length; j++) ds[j] = mo.d1 + (ds[j] - mo.d1) * (G / got);
    d = next.d0;
  }
  ts.push(t); ds.push(L); zs.push(MOMENTS[MOMENTS.length - 1].z);
  return { ts, ds, zs, dt, end: t };
}
function planAt(t) { const u = clamp(t - INTRO, 0, PLAN.end), i = Math.min(PLAN.ts.length - 2, Math.floor(u / PLAN.dt)), f = clamp((u - PLAN.ts[i]) / PLAN.dt); return { d: lerp(PLAN.ds[i], PLAN.ds[i + 1], f), z: lerp(PLAN.zs[i], PLAN.zs[i + 1], f) }; }
function progress(t) { return planAt(t).d / L; }
function timeAt(d) { let lo = 0, hi = PLAN.ds.length - 1; while (hi - lo > 1) { const m = (lo + hi) >> 1; if (PLAN.ds[m] <= d) lo = m; else hi = m; } return INTRO + PLAN.ts[lo]; }
const travelEnd = () => INTRO + PLAN.end;

// ---------------- the arrow's path: the GPS track gently smoothed ----------------
const mercX = lon => lon * 20037508.34 / 180, mercY = lat => Math.log(Math.tan((90 + lat) * Math.PI / 360)) * 6378137;
const unX = x => x * 180 / 20037508.34, unY = y => 360 / Math.PI * Math.atan(Math.exp(y / 6378137)) - 90;
let SM;   // the route resampled every 8 m along its length and smoothed over ~40 m
function buildPath() {
  const step = 8, n = Math.max(2, Math.ceil(L / step) + 1), x = new Float64Array(n), y = new Float64Array(n);
  for (let i = 0; i < n; i++) { const p = at(Math.min(L, i * step)); x[i] = mercX(p.lon); y[i] = mercY(p.lat); }
  const sx = new Float64Array(n), sy = new Float64Array(n), r = 10, sig = 5;
  for (let i = 0; i < n; i++) { let a = 0, b = 0, w = 0; for (let j = -r; j <= r; j++) { const k = clamp(i + j, 0, n - 1), q = Math.exp(-j * j / (2 * sig * sig)); a += x[k] * q; b += y[k] * q; w += q; } sx[i] = a / w; sy[i] = b / w; }
  SM = { step, n, x: sx, y: sy };
}
function smAt(d) { const u = clamp(d / SM.step, 0, SM.n - 1), i = Math.min(SM.n - 2, Math.floor(u)), f = u - i; return [lerp(SM.x[i], SM.x[i + 1], f), lerp(SM.y[i], SM.y[i + 1], f)]; }
function smLonLat(d) { const [x, y] = smAt(d); return [unX(x), unY(y)]; }
function gauss(arr, sigma) {
  const res = [], n = arr.length, r = Math.ceil(sigma * 3);
  for (let i = 0; i < n; i++) { let s = 0, w = 0; for (let j = -r; j <= r; j++) { const k = clamp(i + j, 0, n - 1), q = Math.exp(-j * j / (2 * sigma * sigma)); s += arr[k] * q; w += q; } res.push(s / w); }
  return res;
}
let CAM;
function buildCamera() {
  buildPath();
  const N = Math.round(T * FPS) + 1, raw = { b: [], up: [] }, plan = [];
  let prevB = null;
  for (let i = 0; i < N; i++) {
    const pl = planAt(i / FPS), v = Math.max(1, (planAt((i + 1) / FPS).d - planAt((i - 1) / FPS).d) * FPS / 2); plan.push(pl);
    // Anticipate bends: look ahead about a second of travel (more when high up and fast).
    const K = clamp(v * 1.1, 60, 25000), a = smAt(pl.d - K * .2), b = smAt(pl.d + K);
    let br = toDeg(Math.atan2(b[0] - a[0], b[1] - a[1]));
    if (prevB !== null) { while (br - prevB > 180) br -= 360; while (br - prevB < -180) br += 360; }
    prevB = br; raw.b.push(br);
    raw.up.push(skyZones.some(z => pl.d >= z.d0 && pl.d <= z.d1) ? 1 : 0);
  }
  const b = gauss(raw.b, .9 * FPS), up = gauss(raw.up, .7 * FPS);
  CAM = plan.map((pl, i) => {
    const t = i / FPS, s = smooth((t - INTRO) / START);
    const moving = t > INTRO + .2 && t < travelEnd();
    return { d: pl.d, bearing: b[i], zoom: lerp(Z_NAV - 2.6, pl.z, s), pitch: lerp(40, PITCH + 4 * clamp((Z_NAV - pl.z) / 4), s) + 16 * up[i] * s, blur: moving ? 2 : 1 };
  });
}
function camera(t) {
  const k = CAM[clamp(Math.round(t * FPS), 0, CAM.length - 1)], d = planAt(t).d, [lon, lat] = smLonLat(d), p = at(d);
  // The camera keeps the arrow exactly in place on the smoothed path; its heading is smoothed.
  const cam = { center: [lon, lat], zoom: k.zoom, pitch: k.pitch, bearing: k.bearing, pad: 820, blur: k.blur };
  const o = ease((t - (T - OUTRO)) / 2.4);
  if (o > 0) {
    const db = ((OV.bearing - cam.bearing + 540) % 360) - 180;
    cam.center = [lerp(cam.center[0], OV.center[0], o), lerp(cam.center[1], OV.center[1], o)];
    cam.zoom = lerp(cam.zoom, OV.zoom, smooth(o * 1.1)); cam.pitch = lerp(cam.pitch, OV.pitch, o); cam.bearing += db * o; cam.pad = lerp(820, 0, o); cam.blur = 1;
  }
  return { ...cam, d, p: { ...p, lon, lat }, o };
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
  return {
    version: 8, glyphs: 'https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf',
    sources: {
      omt: { type: 'vector', url: 'https://tiles.openfreemap.org/planet' },
      dem: { type: 'raster-dem', tiles: [DEM], encoding: 'terrarium', tileSize: 256, maxzoom: 14 },
      shade: { type: 'raster-dem', tiles: [DEM], encoding: 'terrarium', tileSize: 256, maxzoom: 14 },
      world: { type: 'geojson', data: { type: 'Polygon', coordinates: [[[-180, -85], [180, -85], [180, 85], [-180, 85], [-180, -85]]] } },
      all: { type: 'geojson', data: lineFC(null) },
      done: { type: 'geojson', data: lineFC(0) },
      roadname: { type: 'geojson', data: EMPTY },
      placename: { type: 'geojson', data: EMPTY },
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
      // The road's name painted on the road ahead, facing the driver.
      { id: 'roadname', type: 'symbol', source: 'roadname', layout: { 'text-field': ['get', 'label'], 'text-font': ['Noto Sans Bold'], 'text-size': 48, 'text-letter-spacing': .1, 'text-max-width': 30,
        'text-rotate': ['get', 'rot'], 'text-rotation-alignment': 'map', 'text-pitch-alignment': 'map', 'text-allow-overlap': true, 'text-ignore-placement': true },
        paint: { 'text-color': '#ffffff', 'text-opacity': 0, 'text-halo-color': 'rgba(0,0,0,.22)', 'text-halo-width': 1.5, 'text-halo-blur': 1 } },
      { id: 'placename', type: 'symbol', source: 'placename', layout: { 'text-field': ['get', 'label'], 'text-font': ['Noto Sans Regular'], 'text-size': 34, 'text-letter-spacing': .06, 'text-max-width': 10, 'text-allow-overlap': true, 'text-ignore-placement': true },
        paint: { 'text-color': '#ffffff', 'text-opacity': 0, 'text-halo-color': 'rgba(0,0,0,.45)', 'text-halo-width': 1.8, 'text-halo-blur': 1.2 } },
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
// Draws the map until every tile it needs is in. Each drawing is asked for directly, so this keeps
// going when the screen isn't refreshing (the app in the background).
const nap = () => document.hidden ? new Promise(r => { const ch = new MessageChannel(); ch.port1.onmessage = () => r(); ch.port2.postMessage(0); }) : sleep(8);
async function waitMap() {
  const done = () => map.loaded() && map.areTilesLoaded() && (!map.terrain || map.terrain.sourceCache.sourceCache.loaded());
  const t0 = performance.now();
  map.redraw();
  while (!done() && performance.now() - t0 < 20000) { await nap(); map.redraw(); }
  map.redraw();
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

// ---------------- names: the road painted on the road, the nearest village or town ----------------
// Read from the map tiles while the map is fetched before filming, so nothing extra is downloaded
// and every frame agrees. Inside a city only the road is named; high up only towns are.
const latinName = p => p['name:en'] || p.name_en || p['name:latin'] || p.name || '';
// The road's number when it has one ("NH 48"), otherwise its name.
function roadText(p) {
  const refs = String(p.ref || '').split(';').map(s => s.trim()).filter(Boolean);
  const ref = refs.find(r => /^(NH|SH|MDR)/i.test(r)) || refs[0];
  if (ref) return ref.replace(/^([A-Za-z]+)[\s-]*(\d)/, '$1 $2');
  return latinName(p) || null;
}
// Distance in metres from (lon, lat) to a polyline [[lon, lat], ...].
function nearLine(lon, lat, line) {
  const kx = 111320 * Math.cos(toRad(lat)), ky = 110540; let best = Infinity;
  for (let i = 1; i < line.length; i++) {
    const ax = (line[i - 1][0] - lon) * kx, ay = (line[i - 1][1] - lat) * ky, bx = (line[i][0] - lon) * kx, by = (line[i][1] - lat) * ky;
    const dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy, u = l2 > 0 ? clamp(-(ax * dx + ay * dy) / l2) : 0;
    best = Math.min(best, Math.hypot(ax + u * dx, ay + u * dy));
  }
  return best;
}
const metres = (a, b, lat) => Math.hypot((a[0] - b[0]) * 111320 * Math.cos(toRad(lat)), (a[1] - b[1]) * 110540);
const SEEN = { roads: [], places: new Map() };
// One look at the map around the arrow (while fetching): which road it is on, which places are near.
function collect(t) {
  const pl = planAt(t), [lon, lat] = smLonLat(pl.d), tol = clamp(mppAt(pl.z) * 6, 15, 60);
  let key = null, best = tol;
  for (const f of map.querySourceFeatures('omt', { sourceLayer: 'transportation_name' })) {
    const g = f.geometry, lines = g.type === 'LineString' ? [g.coordinates] : g.type === 'MultiLineString' ? g.coordinates : [];
    for (const line of lines) { const dd = nearLine(lon, lat, line); if (dd < best) { const k = roadText(f.properties); if (k) { best = dd; key = k; } } }
  }
  let city = false;
  for (const f of map.querySourceFeatures('omt', { sourceLayer: 'place' })) {
    const p = f.properties, n = latinName(p); if (!n || f.geometry.type !== 'Point') continue;
    const xy = f.geometry.coordinates, id = n + '|' + xy[0].toFixed(3) + '|' + xy[1].toFixed(3);
    if (!SEEN.places.has(id)) SEEN.places.set(id, { name: n, cls: p.class, lon: xy[0], lat: xy[1] });
    const dd = metres(xy, [lon, lat], lat);
    if ((p.class === 'city' && dd < 8000) || (['suburb', 'quarter', 'neighbourhood'].includes(p.class) && dd < 1200)) city = true;
  }
  SEEN.roads.push({ t, d: pl.d, z: pl.z, key, city });
}
let ROAD_EVENTS = [], PLACE_SHOWS = [], START_NAME = null, END_NAME = null;
const HL_WINDOWS = () => D.highlights.map(h => [timeAt(h.d) - .3, timeAt(h.d) + 2.9]);
const ROAD_SECS = 3, PLACE_PRE = 2.2, PLACE_POST = .5;
function planNames() {
  const hl = HL_WINDOWS(), busy = (a, b) => hl.some(([x, y]) => a < y && b > x);
  // Roads: a change counts once the new road holds for a while; nothing while the camera is high.
  let cur = null, cand = null, cT = 0, cD = 0, n = 0;
  const changes = [];
  for (const s of SEEN.roads) {
    if (!s.key || s.z < 14) continue;
    if (s.key === cur) { cand = null; continue; }
    if (s.key !== cand) { cand = s.key; cT = s.t; cD = s.d; n = 1; } else n++;
    if (n >= 4 && s.d - cD >= 100) { cur = cand; changes.push({ t: Math.max(cT, INTRO + .8), text: cur }); cand = null; }
  }
  ROAD_EVENTS = [];
  for (const e of changes) {
    let s = e.t; const last = ROAD_EVENTS[ROAD_EVENTS.length - 1];
    if (last && s < last.s + ROAD_SECS + .3) s = last.s + ROAD_SECS + .3;
    for (const [x, y] of hl) if (s < y && s + ROAD_SECS > x) s = y + .2;
    if (s - e.t > 5 || s + ROAD_SECS > travelEnd() - .5) continue;      // too late to be about this road
    if (planAt(s).z < 14) continue;
    if (last && last.text === e.text) continue;
    const d = planAt(s).d + mppAt(planAt(s).z) * 560, [lon, lat] = smLonLat(d), a = smAt(d - 30), b = smAt(d + 30);
    ROAD_EVENTS.push({ s, text: e.text, lon, lat, rot: toDeg(Math.atan2(b[0] - a[0], b[1] - a[1])) });
  }
  // Places: where the route passes closest to each one, and how close.
  const cityAt = d => { let k = null; for (const s of SEEN.roads) if (!k || Math.abs(s.d - d) < Math.abs(k.d - d)) k = s; return k ? k.city : false; };
  const cands = [];
  for (const p of SEEN.places.values()) {
    if (!['village', 'town', 'city'].includes(p.cls)) continue;
    const px = mercX(p.lon), py = mercY(p.lat), k = Math.cos(toRad(p.lat));
    let best = Infinity, bi = 0;
    for (let i = 0; i < SM.n; i += 4) { const dd = Math.hypot(SM.x[i] - px, SM.y[i] - py); if (dd < best) { best = dd; bi = i; } }
    const d = bi * SM.step, near = best * k, z = planAt(timeAt(d)).z, high = z < 14;
    if (high ? p.cls === 'village' || near > Math.max(2500, 70 * mppAt(z)) : p.cls === 'city' || near > 2000) continue;
    if (!high && cityAt(d)) continue;
    cands.push({ ...p, d, near, t: timeAt(d), rank: (p.cls === 'village' ? 1 : 0) + near / 5000 });
  }
  cands.sort((a, b) => a.rank - b.rank);
  const taken = [[-1, INTRO + 3.4], [travelEnd() - .6, T + 1], ...hl, ...ROAD_EVENTS.map(e => [e.s - .2, e.s + ROAD_SECS])];
  PLACE_SHOWS = [];
  for (const c of cands) {
    const a = c.t - PLACE_PRE, b = c.t + PLACE_POST;
    if (taken.some(([x, y]) => a < y + .3 && b > x - .3)) continue;
    taken.push([a, b]); PLACE_SHOWS.push({ ...c, a, b });
  }
  // Start and end: the names the app knows, else the most precise nearby name from the map.
  const nameNear = d => {
    const [lon, lat] = smLonLat(d); let best = null, bs = Infinity;
    for (const p of SEEN.places.values()) {
      const dd = metres([p.lon, p.lat], [lon, lat], lat), lim = p.cls === 'city' ? 15000 : p.cls === 'town' ? 6000 : 3000;
      if (!['city', 'town', 'village', 'suburb', 'quarter', 'neighbourhood', 'hamlet'].includes(p.cls) || dd > lim) continue;
      const score = dd / lim; if (score < bs) { bs = score; best = p.name; }
    }
    return best;
  };
  START_NAME = D.startName || nameNear(0);
  END_NAME = D.endName || nameNear(L);
}
// Shows this frame's names on the map (only touching the map when something changes).
let shownRoad = null, shownPlace = null;
function applyNames(t, c) {
  const road = ROAD_EVENTS.find(e => t >= e.s && t < e.s + ROAD_SECS) || null;
  if (road !== shownRoad) { shownRoad = road; map.getSource('roadname').setData(road ? { type: 'Feature', geometry: { type: 'Point', coordinates: [road.lon, road.lat] }, properties: { label: road.text, rot: road.rot } } : EMPTY); }
  const ra = road ? smooth((t - road.s) / .5) * (1 - smooth((t - road.s - ROAD_SECS + .6) / .6)) * (1 - c.o) : 0;
  map.setPaintProperty('roadname', 'text-opacity', ra * .92);
  const place = PLACE_SHOWS.find(e => t >= e.a && t < e.b) || null;
  if (place !== shownPlace) { shownPlace = place; map.getSource('placename').setData(place ? { type: 'Feature', geometry: { type: 'Point', coordinates: [place.lon, place.lat] }, properties: { label: place.name } } : EMPTY); }
  let pa = 0;
  if (place) {
    pa = smooth((t - place.a) / .45) * (1 - smooth((t - place.b + .45) / .45)) * (1 - c.o);
    // Fade a name that sits at the edge of the picture.
    const q = map.project([place.lon, place.lat]);
    pa *= clamp(Math.min(q.x - 60, W - 60 - q.x, q.y - 200, H - 120 - q.y) / 80);
  }
  map.setPaintProperty('placename', 'text-opacity', pa);
}
const EMPTY = { type: 'FeatureCollection', features: [] };
// A small card with where the drive starts, and where it ends beside the total.
function placeCard(name, a, y, end) {
  if (!name || a <= 0) return null;
  fx.font = `500 30px ${SANS}`; fx.letterSpacing = '0px'; const w = fx.measureText(name).width + 96, b = box((W - w) / 2, y, w, 66);
  fx.save(); fx.globalAlpha = a; fx.fillStyle = 'rgba(10,12,16,.55)'; fx.strokeStyle = 'rgba(255,255,255,.14)'; fx.lineWidth = 1.5;
  fx.beginPath(); fx.roundRect(b.x, b.y, b.w, b.h, 33); fx.fill(); fx.stroke();
  fx.beginPath(); fx.arc(b.x + 38, b.y + 33, 8, 0, 7);
  if (end) { fx.fillStyle = '#ff7a26'; fx.fill(); } else { fx.strokeStyle = '#ffffff'; fx.lineWidth = 3.5; fx.stroke(); }
  fx.fillStyle = '#ffffff'; fx.textAlign = 'left'; fx.textBaseline = 'middle'; fx.fillText(name, b.x + 64, b.y + 34);
  fx.restore(); return b;
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
      if (d < best) { const k = roadText(f.properties); if (k) { best = d; road = k; } }
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
// Labels during the drive appear beside their point, stay put on screen for 2 s, then fade.
const PINNED = new Map();
function highlights(t, c, arrowAt) {
  const end = clamp((t - (T - OUTRO + 1.5)) / .7), items = [], dots = [], live = [];
  for (const h of D.highlights) {
    if (c.d < h.d - 1 && end <= 0) continue;
    const q = map.project([h.lon, h.lat]);
    const since = t - timeAt(h.d), pop = clamp(since / .35);
    if (q.x > -50 && q.x < W + 50 && q.y > 0 && q.y < H + 50 && !dots.some(o => Math.hypot(o.x - q.x, o.y - q.y) < 22)) dots.push({ x: q.x, y: q.y, a: Math.max(pop, end), r: 8 + (1 - pop) * 10 });
    if (end > 0) { if (q.x > -50 && q.x < W + 50 && q.y > 0 && q.y < H + 50) items.push({ x: q.x, y: q.y, a: end, key: h.key.toUpperCase(), value: h.value }); continue; }
    const a = clamp((since + .2) / .25) * (1 - clamp((since - 2.5) / .4)) * (1 - clamp(c.o * 2));
    if (a > .005 && since > -.2 && since < 2.9) live.push({ h, q, a });
  }
  for (const d of dots) { fx.save(); fx.globalAlpha = d.a; fx.fillStyle = '#ffffff'; fx.strokeStyle = '#ff7a26'; fx.lineWidth = 4; fx.shadowColor = 'rgba(0,0,0,.5)'; fx.shadowBlur = 8; fx.beginPath(); fx.arc(d.x, d.y, d.r, 0, 7); fx.fill(); fx.stroke(); fx.restore(); }
  const obstacles = [box(LOGO.x, LOGO.y, LOGO.w, LOGO.h)];
  if (arrowAt) obstacles.push(box(arrowAt.x - 50, arrowAt.y - 50, 100, 100));
  for (const { h, q, a } of live) {
    let pin = PINNED.get(h);
    if (!pin) {
      const it = { x: q.x, y: q.y, key: h.key.toUpperCase(), value: h.value, where: whereOf(h) };
      const placed = layout([it], [...obstacles, ...[...PINNED.values()].map(p => p.b)])[0];
      if (!placed) continue;
      pin = placed; PINNED.set(h, pin);
    }
    drawLabel({ it: { ...pin.it, x: pin.b.x, y: pin.b.y, a }, b: pin.b });
  }
  if (end > 0) { obstacles.push(box(W / 2 - 330, H - 410, 660, 390), ...dots.map(d => box(d.x - 12, d.y - 12, 24, 24))); layout(items, obstacles).forEach(drawLabel); }
}

// ---------------- one frame ----------------
async function frame(t, warm) {
  const c = camera(t), p = c.p, wx = weather(p.t), k = look(p, wx);
  map.jumpTo({ center: c.center, zoom: c.zoom, pitch: c.pitch, bearing: c.bearing, padding: { top: c.pad, bottom: 0, left: 0, right: 0 } });
  if (!warm) {
    applyLook(k, p); map.getSource('done').setData(lineFC(c.d)); applyNames(t, c);
  }
  await waitMap();
  if (!warm) { draw(t, c, p, k); compose(t); }
  return c;
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
  const endA = clamp((t - (T - OUTRO + 1.3)) / .7);
  totalBox(endA); placeCard(END_NAME, endA, H - 392, true);
  placeCard(START_NAME, clamp((t - .6) / .5) * (1 - clamp((t - (INTRO + 2.8)) / .5)), H - 250, false);
  highlights(t, c, arrowAt);
  if (t > T - 2.4) { fx.save(); fx.globalAlpha = clamp((t - (T - 2.4)) / .8) * .55; fx.font = `400 17px ${SANS}`; fx.fillStyle = '#ffffff'; fx.textAlign = 'center'; fx.fillText(D.credit, W / 2, H - 44); fx.restore(); }
  const fi = clamp(t / .5); if (fi < 1) { fx.fillStyle = `rgba(0,0,0,${1 - fi})`; fx.fillRect(0, 0, W, H); }
}

function arrow(t, c, alpha) {
  const p = c.p, q = map.project([p.lon, p.lat]), mpp = 156543 * Math.cos(toRad(p.lat)) / Math.pow(2, c.zoom), dd = clamp(mpp * 45, 25, 2500);
  const a = smAt(c.d - dd), b = smAt(c.d + dd), qa = map.project([unX(a[0]), unY(a[1])]), qb = map.project([unX(b[0]), unY(b[1])]);
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
const accC = mk(), ag = accC.getContext('2d');
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
  for (const [codec, kind] of [['avc1.640028', 'avc'], ['avc1.4d0028', 'avc'], ['avc1.42e028', 'avc'], ['vp09.00.40.08', 'vp9']]) {
    const config = { codec, width: W, height: H, bitrate: 14_000_000, framerate: FPS, latencyMode: 'quality' };
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
    map = new maplibregl.Map({ container: 'map', style: style(), center: [route[0].lon, route[0].lat], zoom: Z_NAV - 2.6, pitch: 40, maxPitch: 80, interactive: false, attributionControl: false,
      preserveDrawingBuffer: true, fadeDuration: 0, pixelRatio: 1, maxTileCacheSize: 1500 });
    await new Promise((res, rej) => { map.on('load', res); map.on('error', e => { if (!map.loaded()) rej(e.error || new Error('map')); }); });
    textures(); map.setTerrain({ source: 'dem', exaggeration: 1.5 });
    let w = 180, e = -180, s = 90, n = -90; for (const r of route) { w = Math.min(w, r.lon); e = Math.max(e, r.lon); s = Math.min(s, r.lat); n = Math.max(n, r.lat); }
    const ov = map.cameraForBounds([[w, s], [e, n]], { padding: { top: 260, bottom: 500, left: 130, right: 130 }, bearing: 0 });
    OV = { center: [ov.center.lng, ov.center.lat], zoom: Math.min(ov.zoom, 15), pitch: 0, bearing: 0 };
    buildCamera();
    const frames = Math.min(Math.round(T * FPS), D.limitFrames || Infinity);   // limitFrames: tests only
    // Fetch the map along the way first, so drawing never waits mid-shot, and read the names on it.
    const step = D.stills ? 12 : 6;
    for (let f = 0; f < frames; f += step) { const t = f / FPS; await frame(t, true); if (t <= travelEnd()) collect(t); report(.2 * f / frames, 'map'); }
    planNames();
    if (D.stills) { for (const t of (D.stills === 'highlights' ? D.highlights.map(h => timeAt(h.d) + .3) : D.stills)) { await frame(t, false); await window.__shot(t); } Android.done('stills', T); return; }   // tests only
    const muxer = new Mp4Muxer.Muxer({
      target: new Mp4Muxer.StreamTarget({ onData: (data, position) => Android.chunk(toBase64(data), position), chunked: true, chunkSize: 2 ** 20 }),
      video: { codec: codec.kind, width: W, height: H, frameRate: FPS }, fastStart: false, firstTimestampBehavior: 'offset',
    });
    let failed = null;
    const enc = new VideoEncoder({ output: (chunk, meta) => muxer.addVideoChunk(chunk, meta), error: err => { failed = err; } });
    enc.configure(codec.config);
    for (let f = 0; f < frames; f++) {
      if (failed) throw failed;
      // Motion blur where the ground rushes past: average a few moments across half a frame.
      const n = D.forceBlur || CAM[clamp(f, 0, CAM.length - 1)].blur;   // forceBlur: tests only
      if (n <= 1) await frame(f / FPS, false);
      else for (let j = 0; j < n; j++) {
        await frame((f + (j / (n - 1) - .5) * .5) / FPS, false);
        ag.globalAlpha = j === 0 ? 1 : 1 / (j + 1); ag.drawImage(out, 0, 0);
        if (j === n - 1) { ag.globalAlpha = 1; og.drawImage(accC, 0, 0); }
      }
      const vf = new VideoFrame(out, { timestamp: Math.round(f * 1e6 / FPS), duration: Math.round(1e6 / FPS) });
      enc.encode(vf, { keyFrame: f % (FPS * 2) === 0 }); vf.close();
      while (enc.encodeQueueSize > 2) await new Promise(r => { const go = () => { enc.removeEventListener('dequeue', go); r(); }; enc.addEventListener('dequeue', go); setTimeout(go, 40); });
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
