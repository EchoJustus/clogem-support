<!-- Copyright 2026 EchoJustus. All rights reserved. Part of clogem-support. -->
# The open-source projects' icons

The front page shows these on the cards under **Open-source core
software** (`site/site.edn`, `:open-source`). `bb portal` embeds each one
as a `data:` image, so the page still loads nothing from other sites.

They are the 48-unit masters of the clogem icon family
(`EchoJustus/-sync-logos`, commit `3c18074`, 2026-10-02), copied as they
are:

| File | From | Licence |
|---|---|---|
| `clogem-wmark.svg` | `wmark/svg/wmark.svg` (v2.1) | EPL-2.0, as in clogem-wmark ([`EPL-2.0.txt`](EPL-2.0.txt)) |
| `clogem-press.svg` | `press/svg/press.svg` | EPL-2.0, as in clogem-press ([`EPL-2.0.txt`](EPL-2.0.txt)) |
| `clogem-hstry.svg` | `hstry/svg/hstry.svg` | © 2026 EchoJustus, all rights reserved: clogem-hstry has no licence yet, so its first line says so |

The names and icons are still marks of their projects: their licences
cover the drawings, not the right to use the names (the repository's
README, "License").

To take a new version: copy the master over the file here, keep its
first line, and run `bb test`, which checks that each one is a plain SVG
(no script, no link to anything outside it) that carries its licence.

Wmark Pro's icon isn't here: it comes from its own published page, like
every app's (`scripts/portal.clj`, `app-icon`).
