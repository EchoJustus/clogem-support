# clogem-support

Help, contact and privacy policies for Clogem apps, and the open-source
projects they are built on: **https://echojustus.github.io/clogem-support/**,
also in [繁體中文](https://echojustus.github.io/clogem-support/zh-hant/),
[简体中文](https://echojustus.github.io/clogem-support/zh-hans/),
[Português](https://echojustus.github.io/clogem-support/pt/) and
[Español](https://echojustus.github.io/clogem-support/es/).

- **Ask a question:** [Discussions, Q&A](https://github.com/EchoJustus/clogem-support/discussions/new?category=q-a).
- **Share feedback or an idea:** [Discussions, Ideas](https://github.com/EchoJustus/clogem-support/discussions/new?category=ideas).
- **Report a bug:** [the bug report form](https://github.com/EchoJustus/clogem-support/issues/new?template=bug-report.yml).
  Say which app and version.
- All of these are public: leave out personal details, license keys and
  videos.
- **Business, privacy and legal:** EchoJustus.Studio@outlook.com.
- **Each app's own support address and privacy policy** are on its page:

| App | Support and privacy |
|---|---|
| Wmark Pro: Video Watermarker | https://echojustus.github.io/clogem-support/wmark-pro/ |

The open-source projects (clogem-wmark, clogem-press) take issues and
pull requests in their own repositories.

## How the site is made

The site is this repository's `gh-pages` branch, served by GitHub Pages
(**Settings > Pages**: Deploy from a branch, `gh-pages`, `/ (root)`).

| On gh-pages | What it is | Who writes it |
|---|---|---|
| `index.html`, `404.html`, `.nojekyll` | the front page in English, the page for missing addresses, and "serve the files as they are" | `portal.yml` here, from `site/site.edn` |
| `zh-hant/`, `zh-hans/`, `pt/`, `es/` | the front page in its other languages (below, "Languages") | `portal.yml` here, from `site/i18n/` |
| `<app>/` (`wmark-pro/`, ...) | each app's page: what it is and does, its support and its privacy policy | that app's own repository, through its publishing workflow |

`main` holds only the sources: GitHub counts languages on the default
branch, so built HTML never shows up there.

Every writer keeps to its own files:
- it stages only what it owns (an app its folder; the front page its three
  root files and the languages' folders, `OWNED` in `portal.yml`);
- it never force-pushes;
- if someone else published at the same moment, it starts again from the
  branch as it now is.

The branch protection on `gh-pages` (no force pushes, no deletions) makes
GitHub refuse anything else.

### The front page

Three parts, in this order (owner, 2026-10-02; the apps first since
2026-10-05):

1. **Commercial software:** the apps, each with a button to its store
   (the Microsoft Store for Wmark Pro), its page (features and support)
   and its policy, and a note that tools like clogem-press will get Pro
   editions as they mature.
2. **Open-source core software:** clogem-hstry, clogem-press and
   clogem-wmark, each with its icon and a link to its source (or, while
   the source isn't public, a line that says so).
3. **Feedback & support:** the Discussions categories and the bug report
   form (below, "Feedback"), and the business address for what shouldn't
   be public.

Each section opens with its heading alone, with no overline repeating it
(owner, 2026-10-02).

- `site/site.edn`: its words in English, the projects and apps it lists,
  in order, the store links, the feedback channels and the languages.
- `site/i18n/<language>.edn`: the same words in each other language.
- `icons/`: the open-source projects' icons, copied from the clogem icon
  set (`icons/README.md` says from where, and under which licence).
- `fonts/`: the English and Chinese pages' subsets of their text face
  (below, "Typography").
- `scripts/portal.clj`: the generator, Babashka with the hiccup that ships
  inside it. It lists the apps whose folders are on `gh-pages`, each with
  the icon from its own page (its favicon, a `data:` SVG). An app without
  one gets a plain mark. The projects' icons are embedded from `icons/`,
  each checked to be a plain drawing (no script, nothing outside it).
- **The store button** is our own (a bag we drew, no store's logo). Wmark
  Pro's points at its live listing,
  `https://apps.microsoft.com/detail/9PMP1591Q78R` (`:store :url` in
  `site.edn`).
- Like the apps' pages, each page is one self-contained file:
  - no JavaScript, no cookies, nothing loaded from another site;
  - a Content-Security-Policy that allows only the page's own style (by its
    SHA-256), `data:` images and `data:` fonts (below, "Typography");
  - light and dark, after the visitor's system setting, or as chosen in
    the header's switcher (Auto, Light, Dark). The switcher is three radio
    buttons the stylesheet reads with `:has()`, so it needs no script; with
    no script and no cookie the choice isn't remembered, and each page
    opens on Auto;
  - beside it, the language menu (below, "Languages").

```
bb portal --site-dir pages   # _site/ from a checkout of gh-pages in pages/
bb test                      # the pages' rules, and the workflow's publishing step against a local stand-in for gh-pages
bb fonts                     # the English and Chinese pages' subsets of Clogem Kai, after a change to the words (below, "Typography")
```

Open `_site/index.html` in a browser to look at it.

`.github/workflows/portal.yml`:
- **When:** on changes to the front page's sources on `main`, by hand
  (**Actions > portal > Run workflow**), and after every Pages build. An
  app's first publication then gets its card, and a new icon shows up on
  its own.
- **Tests:** `bb test` runs first, on pull requests too.
- **Commits:** the root files and the languages' folders to `gh-pages`,
  and nothing else.
- **Pages:** commits pushed with a workflow's `GITHUB_TOKEN` start no Pages
  build ("Configuring a publishing source for your GitHub Pages site",
  GitHub Docs). So whenever the live front page isn't the one just built,
  the workflow asks Pages for a build (the Pages permission), waits until
  the page is live, and fails if it isn't after ten minutes.

### Typography

The English and Chinese pages set their text in **Clogem Kai** (owner,
2026-10-26): subsets of [LXGW WenKai](https://github.com/lxgw/LxgwWenKai)
(霞鹜文楷), an open kaishu typeface whose Latin letters come from
Fontworks' Klee, so English is written in the same hand as Chinese.

- **What it sets:** everything but the headings: the leads, the cards'
  words, the notes, the links and buttons, the theme switcher and the
  language menu, the footer.
- **What keeps the sans:** the headings (the page's title, the sections'
  and the cards' headings) and the heavy labels (weight 700: the
  overline, the badges, the store's name). A kaishu at its heaviest is a
  thick brush; the system's sans (Segoe UI, San Francisco, Roboto, and
  for Chinese Microsoft YaHei or JhengHei, PingFang) carries the page's
  structure, and Kai its voice.
- **Portuguese and Spanish keep the sans** throughout (owner, 2026-10-26:
  in Kai, the owner saw characters falling back and accents not
  displayed properly), and so do their words on the other pages: the
  language menu's *Português* and *Español*, and the 404 page's lines.
  `site.edn`'s `:languages` marks the Kai languages with `:kai`.
- **Traditional Chinese** takes LXGW WenKai too (owner, 2026-10-26), which
  has a glyph for every character the page sets (`bb test` checks).
- **Two weights:** regular for the text, and bold for the semibold labels
  (links, buttons, the switcher, the menu), which the browser takes as the
  nearest face.
- **Each Kai page embeds its own two subsets** as WOFF2 `data:` URIs, so it
  still loads nothing from elsewhere: the characters it sets in each
  weight, and printable ASCII. The full font is about 19 MB a weight; the
  subsets, 23 KB (English), 89 KB (Simplified Chinese) and 104 KB
  (Traditional Chinese), a third more in base64. Those pages grew from
  69 KB to 101 KB, 189 KB and 208 KB, and the 404 page from 19 KB to
  55 KB; the Portuguese and Spanish ones stay at 70 KB. A browser shows
  the text at once and swaps the face in (`font-display: swap`).
- **After a change to the words,** `bb test` fails if a page sets a
  character its subset lacks (the page would still work, with that
  character in the sans): run `bb fonts`, which needs fonttools and
  LXGW WenKai (`fonts/README.md`).
- **The licence:** the SIL Open Font License 1.1, which reserves the names
  "LXGW" and "霞鹜" for the unmodified font, so the subsets are renamed
  Clogem Kai and keep the font's copyright and licence (below,
  "License").

### Languages

The front page is in five languages (owner, 2026-10-25), English by
default:

| Language | Address | Written for | `lang` |
|---|---|---|---|
| English | `/clogem-support/` | everyone else too | `en` |
| Traditional Chinese | `/clogem-support/zh-hant/` | any reader of it, in Taiwan's words (軟體, 影片, 浮水印, 專案, 帳號), not Hong Kong's | `zh-Hant-TW` |
| Simplified Chinese | `/clogem-support/zh-hans/` | any reader of it, in the mainland's usage (软件, 视频, 账号) | `zh-Hans` |
| Portuguese | `/clogem-support/pt/` | Brazil, and Portugal too (below) | `pt-BR` |
| Spanish | `/clogem-support/es/` | Spain (tú, vídeo, aplicación), and Latin America too | `es-ES` |

- **No script picks the language.** GitHub Pages serves files as they
  are, and without JavaScript nothing can read the browser's language. So
  English is the root, each other language has its folder, and every page
  has the **language menu** at its top right: a `<details>` dropdown (owner,
  2026-10-25: five names in a row are too many) that opens without a
  script. It lists each language in its own words, in this order: English,
  繁體中文, 简体中文, Português, Español (Traditional Chinese first, owner,
  2026-10-26), and checks the current one.
- **Search engines** find the others from each page: a canonical address,
  an `hreflang` link to every language, and `x-default` for English.
- **The words** come from `site/site.edn` (English) and
  `site/i18n/<id>.edn` (the rest), in the same shape. A translation must
  give every word, keep each `{placeholder}`, and add nothing; `bb portal`
  stops otherwise, and `bb test` checks each language's words and its
  variety (no Hong Kong words in Traditional Chinese, no Portugal's in
  Portuguese, Spain's Spanish). Names stay as they are: the apps', the
  projects', the stores', and *Video Watermarker*, which is part of the
  app's product name in every Store listing.
- **Chinese** is set in Clogem Kai (above, "Typography"), and its headings
  in Chinese fonts: Microsoft YaHei or PingFang SC for Simplified,
  Microsoft JhengHei or PingFang TC for Traditional, after the Latin face,
  so a system never falls back to Japanese forms.
- **An app's page in the reader's language** is linked when its folder has
  one: a subfolder of the language's name with an `index.html`
  (`wmark-pro/zh-hant/`; Wmark Pro's page is in these five languages
  since 2026-10-26). Otherwise the card links to the app's English page
  and says so ("（英文）", "(em inglês)"), with `hreflang="en"`. The
  front page finds out on each build, which runs after every Pages build,
  so an app's new language is linked on its own. GitHub's forms are in
  English, and each translation says so.
- **The 404 page** is one page for the whole site, in English, with a line
  in each other language leading to its front page.
- **No native speaker has checked the translations yet.** When one has,
  note the date and who here.

**Portuguese: why one page, for Brazil.** Brazilian and European
Portuguese share their spelling (the 1990 Orthographic Agreement) and read
each other without trouble, but each sounds foreign to the other's
readers: *você* against the impersonal or *tu*, *aplicativo* against
*aplicação*, *contato* against *contacto*, *arquivo* against *ficheiro*,
*baixar* against *transferir*, *usuário* against *utilizador*, and the
gerund (*estamos respondendo*) against *a* + infinitive. On a page this
short the differences are a handful of words; nothing is misunderstood.
Brazil has about twenty times Portugal's readers, so the page is written
for Brazil, avoiding the words Portugal would find strangest where a
neutral one exists. A `pt-pt` page (the Store listing already has one for
Wmark Pro) is worth adding only if support from Portugal grows: a
translation file, a line in `:languages`, and its folder in `OWNED`.

**Adding a language:** a line in `site.edn`'s `:languages` (its folder
name, its `lang` tag with its variety, who it is for, and its name in its
own words), its translation in `site/i18n/`, and its folder in `OWNED` in
`portal.yml`; `bb test` fails until all three agree. A language in Clogem
Kai (`:kai true`) also needs its subsets (`bb fonts`). An app can never
take a language's folder name.

### Feedback

Feedback lives in this repository, in the open, where we reply and the
answers stay findable. No widget: the pages run no script and load
nothing from other sites, as ADR 0014 of the apps' pages promises, so
they link to GitHub instead of embedding it.

| Channel | Where | Its form |
|---|---|---|
| Share feedback or an idea | Discussions, **Ideas** | `.github/DISCUSSION_TEMPLATE/ideas.yml` |
| Ask a question | Discussions, **Q&A** (answers can be marked) | `.github/DISCUSSION_TEMPLATE/q-a.yml` |
| Report a bug | Issues | `.github/ISSUE_TEMPLATE/bug-report.yml` |

`.github/ISSUE_TEMPLATE/config.yml` turns blank issues off and points
questions and ideas to Discussions. Each form asks which app, and asks
people to leave out personal details, license keys and videos.

**The owner's steps, once** (the links 404 until then):
1. **Settings > General > Features:** tick **Discussions**. GitHub then
   creates its default categories, among them **Ideas** (slug `ideas`)
   and **Q&A** (slug `q-a`), which the forms and links use as they are.
2. **Watch > Custom:** tick **Issues** and **Discussions**, so every new
   one reaches your notifications and email.
3. Optionally, pin a welcome post in **Announcements**.

**Replying:** answer in the thread; in Q&A, **Mark as answer** on the reply
that solved it. An issue that is really a question can be moved with
**Convert to discussion**, and a discussion that is really a bug with
**Create issue from discussion**. GitHub notifies the person who asked.

A widget such as Giscus would put the threads on the pages themselves,
but it runs a script and loads GitHub's frame on every visit, which the
pages' Content-Security-Policy and the privacy policy rule out. It is the
owner's call, with the privacy policy changed first.

### Adding an app

1. The app's repository publishes `<folder>/index.html` to `gh-pages`, with
   its icon as a `data:image/svg+xml` favicon. Its folder can't be a
   language's (`zh-hans`, `zh-hant`, `pt`, `es`, `en`).
2. Add the app to `site/site.edn`'s `:apps`: its folder, name, subtitle, one
   line about it, and two colours from its icon for its card; and its line
   (and its store's note) in each `site/i18n/` file.

It is listed once both are there, in either order.

## Store trailers

`trailers/<app>/` holds an app's Microsoft Store trailer: the video, its
thumbnail, closed captions in every listing language, and everything it
was made from. The toolchain is Babashka and Clojure over FFmpeg
(`scripts/trailer/`):

```
bb trailer-footage                  # the open footage, pinned by SHA-256
bb trailer [--quality store]        # cut, music, captions, thumbnail, and the Store's checks
clojure -M:record --display :99 --shots trailers/wmark-pro/recording.edn --out target/take.mkv
                                    # record an app on an X display, driven like a person would
```

| Trailer | |
|---|---|
| Wmark Pro: Video Watermarker | [`trailers/wmark-pro`](trailers/wmark-pro/README.md): 55 s, captions in 18 languages |

## License

- **Code** (`scripts/`, `test/`, `.github/`, `bb.edn`, `deps.edn` and
  `.gitignore`): MIT, see [LICENSE](LICENSE).
- **The open-source projects' icons** in `icons/` keep their projects'
  licences: clogem-wmark's and clogem-press's are EPL-2.0
  (`icons/EPL-2.0.txt`), clogem-hstry's is all rights reserved
  (`icons/README.md`).
- **Third-party parts** keep their own licences: the footage in
  `trailers/` is the U.S. Bureau of Land Management's, public domain,
  and the fonts in `trailers/*/sources/fonts/` are under the
  SIL Open Font License 1.1
  (each trailer's README credits them).
- **The fonts in `fonts/`** are subsets of LXGW WenKai, under the SIL
  Open Font License 1.1 (`fonts/OFL-LXGW-WenKai.txt`, with its
  copyright), renamed Clogem Kai because the licence reserves the names
  "LXGW" and "霞鹜" for the unmodified font; the pages embed them, which
  the licence allows, and it doesn't cover the pages (`fonts/README.md`).
- **Everything else:** © 2026 EchoJustus, all rights reserved. That covers
  the words in `site/`, every page on the `gh-pages` branch (including the
  apps' support pages and privacy policies), the trailers in `trailers/`
  (videos, captions, words, recordings), and the names, logos and icons
  of Clogem and its apps (the drawings in `icons/` under their projects'
  licences, above). The MIT license doesn't cover any of it, and no
  licence here grants a right to use the names or icons as marks.
