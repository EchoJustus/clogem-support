# clogem-support

Help, contact and privacy policies for Clogem apps, and the open-source
projects they are built on: **https://echojustus.github.io/clogem-support/**

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
| `index.html`, `404.html`, `.nojekyll` | the front page, the page for missing addresses, and "serve the files as they are" | `portal.yml` here, from `site/site.edn` |
| `<app>/` (`wmark-pro/`, ...) | each app's support and privacy page | that app's own repository, through its publishing workflow |

`main` holds only the sources: GitHub counts languages on the default
branch, so built HTML never shows up there.

Every writer keeps to its own files:
- it stages only what it owns (an app its folder; the front page its three
  files);
- it never force-pushes;
- if someone else published at the same moment, it starts again from the
  branch as it now is.

The branch protection on `gh-pages` (no force pushes, no deletions) makes
GitHub refuse anything else.

### The front page

Three parts, in this order (owner, 2026-10-02):

1. **Open-source core software:** clogem-hstry, clogem-press and
   clogem-wmark, each with its icon and a link to its source (or, while
   the source isn't public, a line that says so).
2. **Commercial software:** the apps, each with a button to its store
   (the Microsoft Store for Wmark Pro), its support page and its policy,
   and a note that tools like clogem-press will get Pro editions as they
   mature.
3. **Feedback & support:** the Discussions categories and the bug report
   form (below, "Feedback"), and the business address for what shouldn't
   be public.

- `site/site.edn`: its words, the projects and apps it lists, in order,
  the store links and the feedback channels.
- `icons/`: the open-source projects' icons, copied from the clogem icon
  set (`icons/README.md` says from where, and under which licence).
- `scripts/portal.clj`: the generator, Babashka with the hiccup that ships
  inside it. It lists the apps whose folders are on `gh-pages`, each with
  the icon from its own page (its favicon, a `data:` SVG). An app without
  one gets a plain mark. The projects' icons are embedded from `icons/`,
  each checked to be a plain drawing (no script, nothing outside it).
- **The store button** is our own (a bag we drew, no store's logo). Wmark
  Pro's points at `https://apps.microsoft.com/`, a placeholder, until its
  listing is live; then `:store :url` in `site.edn` becomes
  `https://apps.microsoft.com/detail/9PMP1591Q78R`.
- Like the apps' pages, each page is one self-contained file:
  - no JavaScript, no cookies, nothing loaded from another site;
  - a Content-Security-Policy that allows only the page's own style (by its
    SHA-256) and `data:` images;
  - light and dark, after the visitor's system setting.

```
bb portal --site-dir pages   # _site/ from a checkout of gh-pages in pages/
bb test                      # the pages' rules, and the workflow's publishing step against a local stand-in for gh-pages
```

Open `_site/index.html` in a browser to look at it.

`.github/workflows/portal.yml`:
- **When:** on changes to the front page's sources on `main`, by hand
  (**Actions > portal > Run workflow**), and after every Pages build. An
  app's first publication then gets its card, and a new icon shows up on
  its own.
- **Tests:** `bb test` runs first, on pull requests too.
- **Commits:** the three root files to `gh-pages`, and nothing else.
- **Pages:** commits pushed with a workflow's `GITHUB_TOKEN` start no Pages
  build ("Configuring a publishing source for your GitHub Pages site",
  GitHub Docs). So whenever the live front page isn't the one just built,
  the workflow asks Pages for a build (the Pages permission), waits until
  the page is live, and fails if it isn't after ten minutes.

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
   its icon as a `data:image/svg+xml` favicon.
2. Add the app to `site/site.edn`'s `:apps`: its folder, name, subtitle, one
   line about it, and two colours from its icon for its card.

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
| Wmark Pro: Video Watermarker | [`trailers/wmark-pro`](trailers/wmark-pro/README.md): 60 s, captions in 18 languages |

## License

- **Code** (`scripts/`, `test/`, `.github/`, `bb.edn`, `deps.edn` and
  `.gitignore`): MIT, see [LICENSE](LICENSE).
- **The open-source projects' icons** in `icons/` keep their projects'
  licences: clogem-wmark's and clogem-press's are EPL-2.0
  (`icons/EPL-2.0.txt`), clogem-hstry's is all rights reserved
  (`icons/README.md`).
- **Third-party parts** keep their own licences: the open films' footage
  in `trailers/` is © Blender Foundation under CC BY 3.0, and the fonts in
  `trailers/*/sources/fonts/` are under the SIL Open Font License 1.1
  (each trailer's README credits them).
- **Everything else:** © 2026 EchoJustus, all rights reserved. That covers
  the words in `site/`, every page on the `gh-pages` branch (including the
  apps' support pages and privacy policies), the trailers in `trailers/`
  (videos, captions, words, recordings), and the names, logos and icons
  of Clogem and its apps (the drawings in `icons/` under their projects'
  licences, above). The MIT license doesn't cover any of it, and no
  licence here grants a right to use the names or icons as marks.
