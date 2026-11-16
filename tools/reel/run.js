const { chromium } = require('playwright'); const fs = require('fs'); const { spawn } = require('child_process');
(async () => {
  const mode = process.argv[2]; const b = await chromium.launch(); const p = await b.newPage({ viewport: { width: 1080, height: 1920 }, deviceScaleFactor: 1 });
  p.on('pageerror', e => console.log('ERR', e.message)); p.on('console', m => m.type() === 'error' && console.log('CONSOLE', m.text()));
  await p.goto('file://' + __dirname + '/reel.html');
  await p.evaluate(async G => { await document.fonts.load('300 80px "Inter Display"'); await document.fonts.load('600 30px "Inter"'); await document.fonts.load('500 30px "Inter"'); await document.fonts.load('600 70px "Inter Display"'); await document.fonts.load('500 60px "Inter Display"'); init(G); }, JSON.parse(fs.readFileSync(__dirname + '/glyphs.json')));
  if (mode === 'preview') {
    const ts = process.argv[3].split(',').map(Number);
    for (const t of ts) { await p.evaluate(t => render(t), t); await p.screenshot({ path: `${__dirname}/f-${t}.jpg`, type: 'jpeg', quality: 85 }); }
  } else {
    const ff = spawn('ffmpeg', ['-y', '-loglevel', 'error', '-f', 'image2pipe', '-framerate', '30', '-i', '-', '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-crf', '17', '-preset', 'slow', '-profile:v', 'high', '-movflags', '+faststart', process.argv[3]], { stdio: ['pipe', 'inherit', 'inherit'] });
    const N = 450, t0 = Date.now();
    for (let f = 0; f < N; f++) { await p.evaluate(t => render(t), f / 30); const buf = await p.screenshot({ type: 'jpeg', quality: 95 }); if (!ff.stdin.write(buf)) await new Promise(r => ff.stdin.once('drain', r)); if (f % 90 === 0) console.log('frame', f, ((Date.now() - t0) / 1000).toFixed(0) + 's'); }
    ff.stdin.end(); await new Promise(r => ff.on('close', r));
  }
  await b.close();
})();
