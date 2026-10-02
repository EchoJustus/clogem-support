# clogem-support

Help, contact and privacy policies for Clogem apps:
**https://echojustus.github.io/clogem-support/**

- **Report a problem or suggest a feature:** [open an issue](https://github.com/EchoJustus/clogem-support/issues),
  and say which app and version. Issues are public: leave out personal
  details, license keys and videos.
- **Business, privacy and legal:** EchoJustus.Studio@outlook.com.
- **Each app's own support address and privacy policy** are on its page:

| App | Support and privacy |
|---|---|
| Wmark Pro: Video Watermarker | https://echojustus.github.io/clogem-support/wmark-pro/ |

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

- `site/site.edn`: its words, and the apps it lists, in order.
- `scripts/portal.clj`: the generator, Babashka with the hiccup that ships
  inside it. It lists the apps whose folders are on `gh-pages`, each with
  the icon from its own page (its favicon, a `data:` SVG). An app without
  one gets a plain mark.
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
| Wmark Pro: Video Watermarker | [`trailers/wmark-pro`](trailers/wmark-pro/README.md): 54 s, captions in 18 languages |

## License

- **Code** (`scripts/`, `test/`, `.github/`, `bb.edn`, `deps.edn` and
  `.gitignore`): MIT, see [LICENSE](LICENSE).
- **Third-party parts** keep their own licences: the open films' footage
  in `trailers/` is © Blender Foundation under CC BY 3.0, and the fonts in
  `trailers/*/sources/fonts/` are under the SIL Open Font License 1.1
  (each trailer's README credits them).
- **Everything else:** © 2026 EchoJustus, all rights reserved. That covers
  the words in `site/`, every page on the `gh-pages` branch (including the
  apps' support pages and privacy policies), the trailers in `trailers/`
  (videos, captions, words, recordings), and the names, logos and icons
  of Clogem and its apps. The MIT license doesn't cover any of it, and it
  grants no right to use the names or icons.
