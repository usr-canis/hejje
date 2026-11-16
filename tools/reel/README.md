# Promo reel

Renders the 15-second 9:16 reel (1080 × 1920, 30 fps): a night drive whose trails spell ಹೆಜ್ಜೆ.

```sh
pip install --target ./py fonttools uharfbuzz
PYTHONPATH=./py python3 glyphs.py      # outlines of ಹೆಜ್ಜೆ from the app's Mallige font -> glyphs.json
node run.js preview 2,6,11,14          # a few frames as f-<t>.jpg
node run.js video hejje-reel.mp4       # the video (needs Playwright's Chromium and ffmpeg)
```

`reel.html` draws every frame on a canvas from the time alone, so any frame can be re-rendered.
