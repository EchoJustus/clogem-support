<!-- Copyright 2026 EchoJustus. All rights reserved. Part of clogem-support. -->
# Clogem Kai: the pages' text face

The front page's English and Chinese pages, and the 404 page, set their
text in **LXGW WenKai** (霞鹜文楷), a kaishu-style typeface with Latin from
Fontworks' Klee (owner, 2026-10-26). Headings and heavy labels (weight
700: the page title, section and card headings, badges and the store's
name) keep the sans, and so do the Portuguese and Spanish pages and
words. The README, "Typography", says how the pages use it.

## What is here

| File | What it is |
|---|---|
| `<page>-regular.woff2`, `<page>-bold.woff2` | each Kai page's subset, in its two weights: `en`, `zh-hant`, `zh-hans`, and `404` |
| `subsets.edn` | what each subset holds, by page and weight, and the font they were cut from, with its SHA-256 |
| `OFL-LXGW-WenKai.txt` | the font's licence, with its copyright and Reserved Font Names |

A page embeds only its own two subsets, as `data:` URIs: it loads nothing
from other sites. A subset holds the characters its page sets in that
weight, and printable ASCII:

| Page | Regular | Bold |
|---|---|---|
| English | 13 KB | 11 KB |
| Traditional Chinese | 78 KB | 26 KB |
| Simplified Chinese | 67 KB | 22 KB |
| 404 | 17 KB | 10 KB |

## The source

| Font | Version | From | SHA-256 |
|---|---|---|---|
| LXGW WenKai Regular, Bold | 1.315 | Ubuntu's package `fonts-lxgw-wenkai` 1.315+repack-1 (noble), rebuilt from [lxgw/LxgwWenKai](https://github.com/lxgw/LxgwWenKai)'s source | `6cbc11b7…`, `a2692a0c…` |

Traditional Chinese takes it too (owner, 2026-10-26), not LXGW WenKai TC.
`scripts/fonts.clj` holds the full digests and refuses any other file.

## Making them again

After a change to the words (`site/site.edn`, `site/i18n/`), `bb test`
fails if a page sets a character its subset lacks; the page would still
work, with that character in the sans. Then:

```
apt install fonts-lxgw-wenkai    # LXGW WenKai, where bb fonts looks for it (--main DIR elsewhere)
pip install fonttools brotli     # pyftsubset, ttx and WOFF2
bb fonts                         # every Kai page's subsets, and subsets.edn
```

## Licence

LXGW WenKai is under the **SIL Open Font License 1.1**, and so are these
subsets, which are modified versions of it: see `OFL-LXGW-WenKai.txt`.
LXGW WenKai reserves the names "LXGW" and "霞鹜" for the unmodified font,
so the subsets don't carry its names: `bb fonts` keeps only its copyright
and licence records and names them **Clogem Kai**, which the pages call
the face too. The licence lets fonts be embedded in pages; it doesn't
cover the pages themselves.
