# Regenerating the concepts PDF

The PDF is rendered from `ReconPilot-Concepts-and-Rebuild-Guide.html` using a
headless Chromium browser, which gives far better typography for a long
technical document than a PDF library would.

```bash
BRAVE="/Applications/Brave Browser.app/Contents/MacOS/Brave Browser"

"$BRAVE" --headless --disable-gpu --no-sandbox \
  --print-to-pdf="docs/ReconPilot-Concepts-and-Rebuild-Guide.pdf" \
  --no-pdf-header-footer --virtual-time-budget=10000 \
  "file://$PWD/docs/ReconPilot-Concepts-and-Rebuild-Guide.html"
```

Any Chromium-based browser works: Chrome, Brave or Edge. Point the `BRAVE`
variable at whichever is installed.

## Two layout traps, recorded because they cost time

**`@page :first { margin: 0 }` is not reliably honoured** by Chrome's print
path. A full-bleed cover sized to `297mm` overflowed onto page two and
overlapped the next chapter. The cover is therefore sized to fit *inside* the
normal page box (`240mm`) rather than bleeding to the sheet edge.

**Flexbox does not paginate reliably in print.** The cover originally used
`display:flex` with `margin-top:auto` to push the footer down; Chrome split it
across two pages and interleaved the text. Plain block flow with explicit
margins renders correctly.
