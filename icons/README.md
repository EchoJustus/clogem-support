<!-- Copyright 2026 EchoJustus. All rights reserved. Part of clogem-support. -->
# The icons: the open-source projects' and the hub's own

The front page shows the projects' icons on the cards under **Open-source
core software** (`site/site.edn`, `:open-source`). `bb portal` embeds each
one as a `data:` image, so the page still loads nothing from other sites.
The hub's own mark is in its own section below.

They come from the clogem icon family (`EchoJustus/-sync-logos`):

| File | From | Licence |
|---|---|---|
| `clogem-wmark.svg` | `wmark/svg/wmark.svg`, the 48-unit master (v2.1; commit `3c18074`) | EPL-2.0, as in clogem-wmark ([`EPL-2.0.txt`](EPL-2.0.txt)) |
| `clogem-press.png` | `press/png/press-128.png`, the v3 redesign (commit `63b9e5c`, 2026-10-02) | EPL-2.0, as in clogem-press ([`EPL-2.0.txt`](EPL-2.0.txt)) |
| `clogem-hstry.png` | `hstry/png/hstry-128.png`, the v3 redesign (commit `63b9e5c`, 2026-10-02) | © 2026 EchoJustus, all rights reserved: clogem-hstry has no licence yet |

The PNGs are the 128-pixel renders: the cards show icons at 56 pixels, so
128 stays sharp on high-density screens (the owner's choice of the PNG
renders, 2026-10-02). A PNG carries no header, so this table is where
their licences are stated.

The names and icons are still marks of their projects: their licences
cover the drawings, not the right to use the names (the repository's
README, "License").

To take a new version: copy the file over the one here (an SVG keeps its
first line, the licence) and run `bb test`. It checks that each SVG is a
plain drawing (no script, no link to anything outside it) and each PNG is
square and at least 112 pixels, and that every licence is stated.

Wmark Pro's icon isn't here: it comes from its own published page, like
every app's (`scripts/portal.clj`, `app-icon`).

## The hub's own mark

The hub's own pages (the front page in every language, and the 404 page)
show the Clogem Support mark in their header and as their favicon: a
frosted blue pane in front of a violet plane and a back plane whose edges
run jade, then amber, the four products' colours in the order of the
title's underline. It replaced the four flat tiles (owner, 2026-10-27).

They come from `support-hub/` in the clogem icon family
(`EchoJustus/-sync-logos`):

| File | From | Licence |
|---|---|---|
| `clogem-support.svg` | `support-hub/svg/clogem-support.svg`, the 48-unit master, shown in the header (commit `8b77d05`, 2026-10-27) | © 2026 EchoJustus, all rights reserved, like Clogem's other names, logos and icons (the repository's README, "License") |
| `clogem-support-favicon.svg` | `support-hub/svg/clogem-support-favicon.svg`, its 32-unit favicon master, drawn to stay sharp at 16 and 32 pixels (commit `8b77d05`) | © 2026 EchoJustus, all rights reserved |

`bb portal` reads both (`scripts/portal.clj`, `hub-mark`) and drops each
one's licence comment. Since the header draws the mark inline, it then
checks the result more strictly than the projects' icons: shapes,
gradients and clip paths alone, with no style, link, animation or script
(`inline-svg-problem`). To take a new version, copy the files over these,
keeping their first line, and run `bb test`. Every id in them starts with `cgBf-`, so
they clash with nothing else on the page. The rest of the set (hinted
16–48 pixel masters, PNGs, an ICO, an Apple touch icon) is in
`support-hub/` in `EchoJustus/-sync-logos`.
