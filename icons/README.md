<!-- Copyright 2026 EchoJustus. All rights reserved. Part of clogem-support. -->
# The open-source projects' icons

The front page shows these on the cards under **Open-source core
software** (`site/site.edn`, `:open-source`). `bb portal` embeds each one
as a `data:` image, so the page still loads nothing from other sites.

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
