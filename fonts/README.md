<!-- Copyright 2026 EchoJustus. All rights reserved. Part of clogem-support. -->
# Clogem Kai: the pages' text face

The front page sets its text in **LXGW WenKai** (霞鹜文楷), a kaishu-style
typeface with Latin from Fontworks' Klee (owner, 2026-10-26). Headings and
heavy labels (weight 700: the page title, section and card headings, badges
and the store's name) keep the sans. The README, "Typography", says how the
pages use it.

## What is here

| File | What it is |
|---|---|
| `<page>-regular.woff2`, `<page>-bold.woff2` | each page's subset, in its two weights: `en`, `zh-hant`, `zh-hans`, `pt`, `es`, and `404` |
| `subsets.edn` | what each subset holds, by page and weight, and the fonts they were cut from, with their SHA-256 |
| `OFL-LXGW-WenKai.txt`, `OFL-LXGW-WenKai-TC.txt` | the fonts' licence, with their copyright and Reserved Font Names |

A page embeds only its own two subsets, as `data:` URIs: it loads nothing
from other sites. A subset holds the characters its page sets in that
weight, and printable ASCII:

| Page | Cut from | Regular | Bold |
|---|---|---|---|
| English, Portuguese, Spanish | LXGW WenKai | about 13 KB | about 11 KB |
| Simplified Chinese | LXGW WenKai | 68 KB | 22 KB |
| Traditional Chinese | LXGW WenKai TC, in Taiwan's character forms | 91 KB | 29 KB |
| 404 | LXGW WenKai | 17 KB | 10 KB |

## The sources

| Font | Version | From | SHA-256 |
|---|---|---|---|
| LXGW WenKai Regular, Bold | 1.315 | Ubuntu's package `fonts-lxgw-wenkai` 1.315+repack-1 (noble), rebuilt from [lxgw/LxgwWenKai](https://github.com/lxgw/LxgwWenKai)'s source | `6cbc11b7…`, `a2692a0c…` |
| LXGW WenKai TC Regular, Bold | 1.330 | Google Fonts' build of [lxgw/LxgwWenkaiTC](https://github.com/lxgw/LxgwWenkaiTC), as npm `@expo-google-fonts/lxgw-wenkai-tc` 0.4.3 | `4e1da195…`, `5abc12ec…` |

`scripts/fonts.clj` holds the full digests and refuses any other file.

## Making them again

After a change to the words (`site/site.edn`, `site/i18n/`), `bb test`
fails if a page sets a character its subset lacks; the page would still
work, with that character in the sans. Then:

```
apt install fonts-lxgw-wenkai    # LXGW WenKai, where bb fonts looks for it (--main DIR elsewhere)
pip install fonttools brotli     # pyftsubset, and WOFF2
bb fonts                         # every page's subsets, and subsets.edn
```

`bb fonts` fetches LXGW WenKai TC itself (with curl, into `target/fonts/`)
and checks every source against its SHA-256.

## Licence

Both fonts are under the **SIL Open Font License 1.1**, and so are these
subsets, which are modified versions of them: see `OFL-LXGW-WenKai.txt` and
`OFL-LXGW-WenKai-TC.txt`. LXGW WenKai reserves the names "LXGW" and "霞鹜"
for unmodified fonts, so the subsets don't carry the fonts' names: `bb
fonts` keeps only their copyright and licence records, and the pages call
the face **Clogem Kai**. The licence lets fonts be embedded in pages; it
doesn't cover the pages themselves.
