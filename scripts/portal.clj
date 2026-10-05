;; Copyright 2026 EchoJustus. Part of clogem-support.
;; SPDX-License-Identifier: MIT
(ns portal
  "The support hub's front page, at https://echojustus.github.io/clogem-support/:

    bb portal                         writes _site/ (index.html, 404.html, .nojekyll)
    bb portal --site-dir pages        ...listing only the apps published in
                                      pages/ (a checkout of gh-pages), each
                                      with the icon from its own page
    bb portal --out DIR               writes DIR instead

  The words and the apps come from site/site.edn. The hub is the gh-pages
  branch of this repository: each app publishes its own folder there (its
  support and privacy page), and this page is the root, with a 404 page for
  any address that isn't there. .github/workflows/portal.yml publishes the
  root files alone and leaves the apps' folders as they are.

  The front page has three parts: the commercial apps (each with where to
  get it, its page and its policy), the open-source projects (each with
  its icon from icons/ and a link to its source), and feedback and support
  in the open, through this repository's GitHub Discussions and issue
  forms (.github/). The apps come first (owner, 2026-10-05).

  Like the apps' pages: one self-contained file each, no JavaScript, nothing
  loaded from another site, and a Content-Security-Policy that allows only
  the page's own style (by its SHA-256) and data: images. An app's icon is
  taken from its published page (the data: URI of its favicon), so nothing
  is copied here from the apps' repositories; the open-source projects'
  icons are files in icons/, embedded as data: URIs."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [hiccup2.core :as h])
  (:import (java.net URLEncoder)
           (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.util Base64)))

(set! *warn-on-reflection* true)

(defn- fail! [& msg] (binding [*out* *err*] (println (str/join " " msg))) (System/exit 1))
(defn- warn! [& msg] (binding [*out* *err*] (println (str "Warning: " (str/join " " msg)))))

;; ---------------------------------------------------------------------------
;; The site and its apps

(defn- text? [v] (and (string? v) (not (str/blank? v))))

(defn- colours? [accent]
  (and (sequential? accent) (= 2 (count accent))
       (every? #(and (string? %) (re-matches #"#[0-9A-Fa-f]{6}" %)) accent)))

(def ^:private one-name #"[a-z0-9][a-z0-9-]*")

(def ^:private github-repo #"https://github\.com/[A-Za-z0-9-]+/[A-Za-z0-9._-]+")

(defn- check-projects [{:keys [title lead projects]}]
  (concat
   (when-not (and (text? title) (text? lead)) [":open-source needs :title and :lead"])
   (when-not (seq projects) [":open-source must list at least one project"])
   (when (and (seq projects) (not (apply distinct? (map :id projects))))
     [":open-source's projects must each have their own :id"])
   (for [{:keys [id name subtitle about icon accent repo license status] :as project} projects
         problem [(when-not (and (string? id) (re-matches one-name id))
                    "its :id must be one lowercase name")
                  (when-not (every? text? [name subtitle about])
                    "it needs :name, :subtitle and :about")
                  (when-not (and (string? icon) (re-matches #"icons/[a-z0-9-]+\.(svg|png)" icon))
                    "its :icon must be an .svg or .png file in icons/")
                  (when-not (colours? accent)
                    "its :accent must be two colours like #7A63DC")
                  (when-not (= 1 (count (remove nil? [repo status])))
                    "it needs a :repo (its source) or a :status (while the source isn't public), not both")
                  (when (and (some? repo) (not (and (string? repo) (re-matches github-repo repo))))
                    "its :repo must be a GitHub repository's https address")
                  (when (and (some? repo) (not (text? license)))
                    "with a :repo, it names its :license")
                  (when (and (some? status) (not (text? status)))
                    "its :status must be text")]
         :when problem]
     (str "project " (pr-str (or id project)) ": " problem))))

(defn- check-apps [commercial apps]
  (concat
   (when-not (every? text? (map commercial [:title :lead :note]))
     [":commercial needs :title, :lead and :note"])
   (when-not (seq apps) [":apps must list at least one app"])
   (when (and (seq apps) (not (apply distinct? (map :folder apps))))
     [":apps must each have their own :folder"])
   (for [{:keys [folder name subtitle about accent store] :as app} apps
         problem [(when-not (and (string? folder) (re-matches one-name folder))
                    "its :folder must be one lowercase folder name")
                  (when (some #(not (text? %)) [name subtitle about])
                    "it needs :name, :subtitle and :about")
                  (when-not (colours? accent)
                    "its :accent must be two colours like #7A63DC")
                  (when (and (some? store)
                             (not (and (map? store) (text? (:name store)) (text? (:note store))
                                       (string? (:url store)) (str/starts-with? (:url store) "https://"))))
                    "its :store needs a :name, a :note and an https :url")]
         :when problem]
     (str "app " (pr-str (or folder app)) ": " problem))))

(defn- check-feedback [{:keys [repo title lead note channels]}]
  (concat
   (when-not (and (string? repo) (re-matches github-repo repo))
     [":feedback's :repo must be a GitHub repository's https address"])
   (when-not (every? text? [title lead note]) [":feedback needs :title, :lead and :note"])
   (when-not (seq channels) [":feedback must list at least one channel"])
   (for [{:keys [badge title about action discussion issue-form] :as channel} channels
         problem [(when-not (every? text? [badge title about action])
                    "it needs :badge, :title, :about and :action")
                  (when-not (= 1 (count (remove nil? [discussion issue-form])))
                    "it needs a :discussion (a category's slug) or an :issue-form, not both")
                  (when (and (some? discussion) (not (and (string? discussion) (re-matches one-name discussion))))
                    "its :discussion must be a category's slug, like q-a")
                  (when (and (some? issue-form) (not (and (string? issue-form) (re-matches #"[a-z0-9-]+\.ya?ml" issue-form))))
                    "its :issue-form must be a form's file name, like bug-report.yml")]
         :when problem]
     (str "feedback channel " (pr-str (or title channel)) ": " problem))))

(defn check-site
  "site.edn's problems, in words."
  [{:keys [url title lead business open-source commercial apps feedback]}]
  (concat
   (for [[k v] {:url url :title title :lead lead :business business}
         :when (not (text? v))]
     (str k " must be text"))
   (when-not (and (string? url) (str/starts-with? url "https://") (str/ends-with? url "/"))
     [":url must be https and end with /"])
   (when-not (and (string? business) (re-matches #"[^\s@<>\"]+@[^\s@<>\"]+\.[A-Za-z]{2,}" business))
     [":business must be an email address"])
   (check-projects open-source)
   (check-apps commercial apps)
   (check-feedback feedback)))

(defn channel-url
  "Where a feedback channel opens: a new discussion in its category, or a
  new issue with its form."
  [repo {:keys [discussion issue-form]}]
  (if discussion
    (str repo "/discussions/new?category=" discussion)
    (str repo "/issues/new?template=" issue-form)))

(defn app-icon
  "The icon an app's published page uses as its favicon: a data:image/svg+xml
  URI, or nil when the page has none (the card then shows a plain mark)."
  [html]
  (when-let [link (re-find #"<link\b[^>]*\brel=\"icon\"[^>]*>" html)]
    (second (re-find #"\bhref=\"(data:image/svg\+xml;base64,[A-Za-z0-9+/]+={0,2})\"" link))))

(defn published-apps
  "The apps to list: with `site-dir` (a checkout of gh-pages), those whose
  folder holds an index.html, each with its icon; without, all of them,
  with no icons (a preview)."
  [apps site-dir]
  (if-not site-dir
    apps
    (keep (fn [{:keys [folder] :as app}]
            (let [page (fs/file site-dir folder "index.html")]
              (if (fs/exists? page)
                (let [icon (app-icon (slurp page))]
                  (when-not icon (warn! folder "has no icon in its page; its card shows a plain mark"))
                  (assoc app :icon icon))
                (do (warn! folder "isn't published yet (no" (str page) "); it isn't listed") nil))))
          apps)))

;; ---------------------------------------------------------------------------
;; The look: the clogem family's hues on a quiet ground, light and dark

(def ^:private family
  "The clogem icon family's colours: wmark's blue, wmark Pro's violet,
  clogem-press's jade, clogem-hstry's amber."
  ["#4F80E3" "#7C62E2" "#23A982" "#E5A23A"])

(defn- hub-mark
  "The hub's own mark: the family's four colours as four rounded tiles."
  []
  (let [[a b c d] family
        tile (fn [x y fill] (format "<rect x=\"%d\" y=\"%d\" width=\"20\" height=\"20\" rx=\"6\" fill=\"%s\"/>" x y fill))]
    (str "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 48 48\">"
         (tile 3 3 a) (tile 25 3 b) (tile 3 25 c) (tile 25 25 d) "</svg>")))

(defn- data-uri [svg]
  (str "data:image/svg+xml;base64,"
       (.encodeToString (Base64/getEncoder) (.getBytes ^String svg StandardCharsets/UTF_8))))

(defn icon-problem
  "Why an open-source project's icon can't go on the page, or nil: it must be
  a plain SVG drawing, with nothing that runs or reaches outside it."
  [svg]
  (cond (not (re-find #"<svg\b" svg))                     "it isn't an SVG"
        (re-find #"(?i)<script|<foreignObject|<image\b" svg) "it has a script, foreign content or an image in it"
        (re-find #"(?i)\son[a-z]+\s*=" svg)                 "it has an event handler"
        (re-find #"(?i)href\s*=\s*[\"'](?!#)" svg)          "it links outside itself"
        (re-find #"(?i)url\(\s*[\"']?(?!#)" svg)            "it refers to something outside itself"))

(def ^:private png-signature [0x89 0x50 0x4E 0x47 0x0D 0x0A 0x1A 0x0A])

(defn png-problem
  "Why a PNG icon can't go on the page, or nil: it must be a PNG, square,
  and at least 112 pixels (twice the card's 56, so it stays sharp on
  high-density screens). Its size is read from the IHDR chunk."
  [^bytes b]
  (let [u8  (fn [i] (bit-and (aget b (int i)) 0xff))
        u32 (fn [i] (reduce (fn [acc k] (+ (* acc 256) (u8 (+ i k)))) 0 (range 4)))]
    (cond (or (< (alength b) 24) (not= png-signature (map u8 (range 8)))) "it isn't a PNG"
          (not= (u32 16) (u32 20))                                       "it isn't square"
          (< (u32 16) 112)                                                "it is smaller than 112 pixels")))

(defn project-icons
  "The projects, each with its icon as a data: URI (:icon-uri), read from
  `root` (this repository): an SVG checked to be a plain drawing, or a PNG
  checked to be square and large enough. Fails when one is missing or
  unfit."
  [projects root]
  (vec (for [{:keys [id icon] :as project} projects]
         (let [f    (fs/file root icon)
               bad! #(fail! (str "project " id ": its icon " icon ": " %))]
           (when-not (fs/exists? f) (fail! (str "project " id ": its icon " icon " isn't there")))
           (if (str/ends-with? icon ".png")
             (let [b (fs/read-all-bytes f)]
               (some-> (png-problem b) bad!)
               (assoc project :icon-uri (str "data:image/png;base64," (.encodeToString (Base64/getEncoder) ^bytes b))))
             (let [svg (slurp f)]
               (some-> (icon-problem svg) bad!)
               (assoc project :icon-uri (data-uri svg))))))))

(def ^:private bag
  "A shopping bag, our own drawing, for the store button (no store's logo:
  those are their owners' marks)."
  (str "<svg class=\"bag\" viewBox=\"0 0 24 24\" aria-hidden=\"true\" focusable=\"false\">"
       "<path fill=\"currentColor\" d=\"M8 7V6a4 4 0 0 1 8 0v1h2.1c.7 0 1.3.5 1.4 1.2l1 11.4A2.2 2.2 0 0 1 18.3 22H5.7a2.2 2.2 0 0 1-2.2-2.4l1-11.4C4.6 7.5 5.2 7 5.9 7H8Zm2 0h4V6a2 2 0 1 0-4 0v1Zm-1 4a1 1 0 0 0-1 1 4 4 0 0 0 8 0 1 1 0 1 0-2 0 2 2 0 1 1-4 0 1 1 0 0 0-1-1Z\"/>"
       "</svg>"))

;; ---------------------------------------------------------------------------
;; The styles

(def ^:private light
  (str "--bg:#F4F6F9;--glow-1:rgba(79,128,227,.16);--glow-2:rgba(124,98,226,.12);"
       "--surface:#FFFFFF;--surface-2:#EEF1F6;--border:rgba(20,28,45,.11);"
       "--text:#141922;--muted:#525C70;--heading:#0D1220;--link:#2456B0;"
       "--chip:#E8EEFB;--chip-ink:#1D4C9E;--focus:#2F6FE4;"
       "--store:#141922;--store-ink:#FFFFFF;--store-hover:#2A3346;"
       "--shadow:0 1px 2px rgba(13,18,32,.05),0 18px 40px -24px rgba(36,86,176,.32)"))

(def ^:private dark
  (str "--bg:#0D1016;--glow-1:rgba(79,128,227,.20);--glow-2:rgba(124,98,226,.18);"
       "--surface:#151A23;--surface-2:#1A202B;--border:rgba(160,175,205,.15);"
       "--text:#E9EDF4;--muted:#A2ABBC;--heading:#F5F7FB;--link:#9FBBFF;"
       "--chip:rgba(159,187,255,.12);--chip-ink:#B9CCFF;--focus:#9FBBFF;"
       "--store:#F5F7FB;--store-ink:#0D1220;--store-hover:#DCE4F5;"
       "--shadow:0 1px 2px rgba(0,0,0,.35),0 24px 48px -24px rgba(0,0,0,.65)"))

(def ^:private colour-tokens
  "The tokens that fade when the theme changes: registered as colours, so
  the page's glows fade with everything else."
  ["--bg" "--glow-1" "--glow-2" "--surface" "--surface-2" "--border" "--text" "--muted"
   "--heading" "--link" "--chip" "--chip-ink" "--focus" "--store" "--store-ink" "--store-hover"])

(defn css
  "The page's styles, one accent rule per app and project (inline style
  attributes would need the CSP to allow them).

  The theme follows the system, or the switcher in the header: three radio
  buttons, read by :has() on the root, so it takes no script. Without a
  script or a cookie the choice isn't remembered: each page opens on
  Auto."
  [apps projects]
  (str/join
   "\n"
   (concat
    (for [t colour-tokens]
      (str "@property " t "{syntax:\"<color>\";inherits:true;initial-value:transparent}"))
    [(str ":root{color-scheme:light dark;" light ";"
          "transition:" (str/join "," (map #(str % " .35s ease") colour-tokens)) "}")
     (str "@media (prefers-color-scheme:dark){:root:not(:has(#theme-light:checked)){color-scheme:dark;" dark "}}")
     (str ":root:has(#theme-dark:checked){color-scheme:dark;" dark "}")
     ":root:has(#theme-light:checked){color-scheme:light}"
     (str "*,*::before{box-sizing:border-box}"
          "html{-webkit-text-size-adjust:100%;text-size-adjust:100%}")
     (str "body{margin:0;min-height:100vh;color:var(--text);background:"
          "radial-gradient(1100px 560px at 8% -12%,var(--glow-1),transparent 62%),"
          "radial-gradient(900px 520px at 100% -4%,var(--glow-2),transparent 58%),var(--bg);"
          "font:400 1rem/1.65 \"Segoe UI Variable Text\",\"Segoe UI\",system-ui,-apple-system,BlinkMacSystemFont,Roboto,\"Helvetica Neue\",Arial,sans-serif;"
          "-webkit-font-smoothing:antialiased}")
     ".page{max-width:62rem;margin:0 auto;padding:clamp(2.5rem,7vw,5.5rem) 1.25rem 2.5rem}"
     "a{color:var(--link);text-underline-offset:.18em}"
     "a:focus-visible{outline:3px solid var(--focus);outline-offset:3px;border-radius:.45rem}"
     "h1,h2,h3{font-family:\"Segoe UI Variable Display\",\"Segoe UI\",system-ui,-apple-system,BlinkMacSystemFont,Roboto,sans-serif;color:var(--heading)}"
     ;; the header
     ".hero{display:grid;gap:1.4rem}"
     ".top{display:flex;flex-wrap:wrap;align-items:center;justify-content:space-between;gap:1rem 1.5rem}"
     ;; the theme switcher: radios as a segmented control
     (str ".theme{display:inline-flex;gap:2px;min-width:0;margin:0;padding:3px;border:1px solid var(--border);"
          "border-radius:999px;background:var(--surface);box-shadow:var(--shadow)}")
     ".theme legend{position:absolute;width:1px;height:1px;overflow:hidden;clip-path:inset(50%);white-space:nowrap}"
     ".theme input{position:absolute;width:1px;height:1px;margin:0;opacity:0}"
     (str ".theme label{display:inline-flex;align-items:center;gap:.4rem;padding:.42rem .85rem;border-radius:999px;"
          "font-size:.86rem;font-weight:650;color:var(--muted);cursor:pointer;transition:background-color .2s ease,color .2s ease}")
     ".theme label:hover{color:var(--text)}"
     ".theme input:checked+label{background:var(--chip);color:var(--chip-ink)}"
     ".theme input:focus-visible+label{outline:3px solid var(--focus);outline-offset:2px}"
     ".theme svg{flex:none;width:1rem;height:1rem}"
     "@supports not selector(:has(*)){.theme{display:none}}"
     ".brand{display:flex;align-items:center;gap:clamp(.9rem,2.5vw,1.25rem)}"
     ".mark{flex:none;width:clamp(3.25rem,8vw,4.25rem);height:clamp(3.25rem,8vw,4.25rem)}"
     ".mark svg{display:block;width:100%;height:100%}"
     ".eyebrow{margin:0 0 .2rem;font-size:.78rem;font-weight:700;letter-spacing:.16em;text-transform:uppercase;color:var(--chip-ink)}"
     "h1{margin:0;font-size:clamp(2.2rem,5.2vw,3.3rem);line-height:1.05;letter-spacing:-.025em;font-weight:700}"
     (str ".rule{width:min(11rem,40%);height:3px;border-radius:3px;background:linear-gradient(90deg,"
          (str/join "," family) ")}")
     ".lead{margin:0;max-width:40rem;font-size:1.15rem;color:var(--muted)}"
     ".jump{display:flex;flex-wrap:wrap;gap:.6rem}"
     (str ".jump a{display:inline-block;padding:.5rem 1.05rem;border-radius:999px;border:1px solid var(--border);"
          "background:var(--surface);color:var(--text);font-weight:600;font-size:.92rem;text-decoration:none;box-shadow:var(--shadow)}")
     ".jump a:hover{border-color:var(--focus)}"
     ;; the sections
     "main{display:grid;gap:clamp(3.25rem,8vw,5rem);margin-top:clamp(3rem,7vw,4.5rem)}"
     "section{scroll-margin-top:1.5rem}"
     "h2{margin:0;font-size:clamp(1.45rem,3.2vw,1.8rem);letter-spacing:-.015em}"
     (str "h2::after{content:\"\";display:block;width:2.6rem;height:3px;margin-top:.65rem;border-radius:3px;"
          "background:linear-gradient(90deg," (str/join "," family) ")}")
     ".section-lead{margin:.9rem 0 1.6rem;max-width:44rem;color:var(--muted)}"
     ;; the cards: apps, projects, feedback channels
     ".apps,.projects,.channels{display:grid;gap:1.25rem}"
     ".apps{grid-template-columns:repeat(auto-fit,minmax(min(100%,24rem),1fr))}"
     ".projects{grid-template-columns:repeat(auto-fit,minmax(min(100%,17rem),1fr))}"
     ".channels{grid-template-columns:repeat(auto-fit,minmax(min(100%,16rem),1fr))}"
     (str ".app,.project{position:relative;overflow:hidden;display:grid;grid-template-columns:auto 1fr;grid-auto-rows:min-content;gap:.35rem 1.1rem;align-items:start;"
          "padding:1.75rem 1.6rem 1.5rem;background:var(--surface);border:1px solid var(--border);border-radius:1.25rem;box-shadow:var(--shadow)}")
     ".app::before,.project::before{content:\"\";position:absolute;inset:0 0 auto 0;height:4px}"
     ".app-icon{grid-row:span 2;width:4rem;height:4rem;display:block}"
     ".project .app-icon{width:3.5rem;height:3.5rem}"
     ".app-icon.plain{border-radius:1rem;background:var(--surface-2);border:1px solid var(--border)}"
     ".app h3,.project h3{margin:.15rem 0 0;font-size:1.25rem;line-height:1.25;overflow-wrap:anywhere}"
     ".app .sub,.project .sub{margin:0;color:var(--muted);font-size:.98rem}"
     ".app .about,.project .about{grid-column:1 / -1;margin:.6rem 0 0}"
     ".project{grid-template-rows:auto auto 1fr auto}"
     (str ".tag{grid-column:1 / -1;justify-self:start;margin-top:.7rem;padding:.25rem .65rem;border-radius:999px;"
          "background:var(--chip);color:var(--chip-ink);font-size:.78rem;font-weight:650}")
     (str ".status{grid-column:1 / -1;margin:.9rem 0 0;padding:.6rem .85rem;border:1.5px dashed var(--border);border-radius:.8rem;"
          "color:var(--muted);font-size:.92rem}")
     ".links{grid-column:1 / -1;display:flex;flex-wrap:wrap;gap:.55rem;margin-top:.9rem}"
     (str ".links a{display:inline-block;padding:.45rem .95rem;border-radius:999px;border:1px solid var(--border);"
          "background:var(--surface-2);color:var(--text);font-weight:600;font-size:.92rem;text-decoration:none}")
     ".links a:hover{border-color:var(--focus)}"
     ".links a.primary{background:var(--chip);color:var(--chip-ink);border-color:transparent}"
     ;; where to get an app
     ".get{grid-column:1 / -1;display:flex;flex-wrap:wrap;align-items:center;gap:.6rem 1rem;margin-top:1.1rem}"
     (str ".store{display:inline-flex;align-items:center;gap:.7rem;min-height:3.25rem;padding:.55rem 1.3rem .55rem 1rem;border-radius:.9rem;"
          "background:var(--store);color:var(--store-ink);text-decoration:none;box-shadow:var(--shadow);transition:background-color .15s ease,transform .15s ease}")
     ".store:hover{background:var(--store-hover);transform:translateY(-1px)}"
     ".store .bag{flex:none;width:1.65rem;height:1.65rem}"
     ".store span{display:grid;line-height:1.15}"
     ".store small{font-size:.72rem;font-weight:600;letter-spacing:.02em;opacity:.82}"
     ".store strong{font-size:1.06rem;font-weight:700;letter-spacing:-.005em}"
     ".store-note{font-size:.86rem;color:var(--muted)}"
     (str ".next{display:flex;gap:1rem;align-items:flex-start;margin-top:1.25rem;padding:1.15rem 1.4rem;"
          "border:1.5px dashed var(--border);border-radius:1.1rem;color:var(--muted)}")
     (str ".next .chip{flex:none;margin-top:.1rem;padding:.25rem .65rem;border-radius:999px;background:var(--chip);color:var(--chip-ink);"
          "font-size:.72rem;font-weight:700;letter-spacing:.11em;text-transform:uppercase}")
     ".next p{margin:0}"
     ;; feedback and support
     (str ".channel{display:flex;flex-direction:column;gap:.5rem;padding:1.5rem 1.45rem 1.35rem;background:var(--surface);"
          "border:1px solid var(--border);border-radius:1.15rem;box-shadow:var(--shadow)}")
     (str ".channel .chip{align-self:flex-start;padding:.26rem .66rem;border-radius:999px;background:var(--chip);color:var(--chip-ink);"
          "font-size:.72rem;font-weight:700;letter-spacing:.11em;text-transform:uppercase}")
     ".channel h3{margin:.15rem 0 0;font-size:1.1rem;line-height:1.3}"
     ".channel p{margin:0 0 .75rem;color:var(--muted)}"
     (str ".button{align-self:flex-start;margin-top:auto;display:inline-flex;align-items:center;gap:.45rem;padding:.55rem 1.05rem;"
          "border-radius:999px;background:var(--chip);color:var(--chip-ink);font-weight:650;font-size:.95rem;text-decoration:none}")
     ".button:hover{background:var(--focus);color:var(--surface)}"
     ".button span{transition:transform .2s ease}"
     ".button:hover span{transform:translateX(3px)}"
     ".fine{margin:1.25rem 0 0;font-size:.95rem;color:var(--muted)}"
     ".help{display:grid;gap:1rem;margin-top:1.25rem}"
     ".help-item{padding:1.25rem 1.5rem;background:var(--surface-2);border:1px solid var(--border);border-radius:1.1rem}"
     ".help-item h3{margin:0 0 .3rem;font-size:1.05rem}"
     ".help-item p{margin:0;color:var(--muted)}"
     ".help-item p+p{margin-top:.45rem}"
     ".help-item .go{font-weight:650;overflow-wrap:anywhere}"
     ".notfound p{margin:.9rem 0 1.4rem;color:var(--muted);font-size:1.1rem}"
     (str "footer{display:flex;flex-wrap:wrap;justify-content:space-between;gap:.4rem 1.5rem;margin-top:clamp(3.5rem,9vw,5.5rem);"
          "padding-top:1.4rem;border-top:1px solid var(--border);font-size:.88rem;color:var(--muted)}")
     "footer p{margin:0}"
     "@media (prefers-reduced-motion:reduce){:root,.theme label,.store,.button span{transition:none}.store:hover,.button:hover span{transform:none}}"
     (str "@media print{:root{--bg:#fff;--surface:#fff;--surface-2:#fff;--text:#000;--muted:#333;--heading:#000;--link:#000;--border:#bbb;--shadow:none}"
          "body{background:#fff}.app::before,.project::before,.jump,.theme{display:none}}")]
    (for [{:keys [folder accent]} apps]
      (format ".app-%s::before{background:linear-gradient(90deg,%s,%s)}" folder (first accent) (second accent)))
    (for [{:keys [id accent]} projects]
      (format ".project-%s::before{background:linear-gradient(90deg,%s,%s)}" id (first accent) (second accent))))))

(defn csp
  "Nothing but the page's own style (by the SHA-256 of `style`) and data:
  images (the favicon and the apps' and projects' icons)."
  [style]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256") (.getBytes ^String style StandardCharsets/UTF_8))]
    (str "default-src 'none'; style-src 'sha256-" (.encodeToString (Base64/getEncoder) digest) "'; "
         "img-src data:; base-uri 'none'; form-action 'none'")))

;; ---------------------------------------------------------------------------
;; The pages

(defn- head [site style title description]
  [:head
   [:meta {:charset "utf-8"}]
   [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
   [:meta {:http-equiv "Content-Security-Policy" :content (csp style)}]
   [:meta {:name "referrer" :content "no-referrer"}]
   [:meta {:name "color-scheme" :content "light dark"}]
   [:meta {:name "theme-color" :media "(prefers-color-scheme: light)" :content "#F4F6F9"}]
   [:meta {:name "theme-color" :media "(prefers-color-scheme: dark)" :content "#0D1016"}]
   [:title title]
   [:meta {:name "description" :content description}]
   [:link {:rel "canonical" :href (:url site)}]
   [:link {:rel "icon" :type "image/svg+xml" :href (data-uri (hub-mark))}]
   [:style (h/raw style)]])

(def ^:private theme-choices
  "The switcher's choices: id, label, what it does, and its icon (our own
  drawings, in the text's colour)."
  [["system" "Auto" "Follow your system's setting"
    "<svg viewBox=\"0 0 16 16\" aria-hidden=\"true\" focusable=\"false\"><circle cx=\"8\" cy=\"8\" r=\"6.25\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.5\"/><path d=\"M8 1.75a6.25 6.25 0 0 1 0 12.5Z\" fill=\"currentColor\"/></svg>"]
   ["light" "Light" "Always light"
    "<svg viewBox=\"0 0 16 16\" aria-hidden=\"true\" focusable=\"false\"><circle cx=\"8\" cy=\"8\" r=\"3\" fill=\"currentColor\"/><path d=\"M8 1v1.6M8 13.4V15M1 8h1.6M13.4 8H15M3.05 3.05l1.13 1.13M11.82 11.82l1.13 1.13M3.05 12.95l1.13-1.13M11.82 4.18l1.13-1.13\" stroke=\"currentColor\" stroke-width=\"1.5\" stroke-linecap=\"round\"/></svg>"]
   ["dark" "Dark" "Always dark"
    "<svg viewBox=\"0 0 16 16\" aria-hidden=\"true\" focusable=\"false\"><path d=\"M13.5 10.3A6 6 0 0 1 5.7 2.5a6 6 0 1 0 7.8 7.8Z\" fill=\"currentColor\"/></svg>"]])

(defn- theme-switcher
  "Auto, Light or Dark, as radio buttons the stylesheet reads (no script)."
  []
  [:fieldset.theme
   [:legend "Colour theme"]
   (for [[id label title icon] theme-choices]
     (list [:input (cond-> {:type "radio" :name "theme" :id (str "theme-" id) :value id}
                     (= id "system") (assoc :checked true))]
           [:label {:for (str "theme-" id) :title title} (h/raw icon) label]))])

(defn- header [site & [jump]]
  [:header.hero
   [:div.top
    [:div.brand
     [:span.mark {:aria-hidden "true"} (h/raw (hub-mark))]
     [:div
      [:p.eyebrow "Clogem"]
      [:h1 "Support"]]]
    (theme-switcher)]
   [:div.rule]
   [:p.lead (:lead site)]
   jump])

(defn- footer [year]
  [:footer
   [:p (str "© " year " EchoJustus.")]
   [:p "This page sets no cookies and loads nothing from other sites."]])

(defn- mailto [email subject]
  (str "mailto:" email "?subject=" (str/replace (URLEncoder/encode ^String subject "UTF-8") "+" "%20")))

(defn- project-card [{:keys [id name subtitle about icon-uri repo license status]}]
  [:article {:class (str "project project-" id)}
   [:img.app-icon {:src icon-uri :alt "" :width "56" :height "56"}]
   [:h3 name]
   [:p.sub subtitle]
   [:p.about about]
   (if repo
     (list
      [:span.tag (str "Open source · " license)]
      [:nav.links {:aria-label (str name " links")}
       [:a.primary {:href repo} "Source on GitHub"]])
     [:p.status status])])

(defn- app-card [{:keys [folder name subtitle about icon store]}]
  [:article {:class (str "app app-" folder)}
   (if icon
     [:img.app-icon {:src icon :alt "" :width "64" :height "64"}]
     [:span.app-icon.plain {:aria-hidden "true"}])
   [:h3 name]
   [:p.sub subtitle]
   [:p.about about]
   (when store
     [:div.get
      [:a.store {:href (:url store) :aria-label (str "Get " name " from the " (:name store))}
       (h/raw bag)
       [:span [:small "Download from the"] [:strong (:name store)]]]
      [:span.store-note (:note store)]])
   [:nav.links {:aria-label (str name " links")}
    [:a.primary {:href (str folder "/")} "Features & support"]
    [:a {:href (str folder "/#privacy")} "Privacy policy"]
    [:a {:href "#feedback"} "Feedback"]]])

(defn index-page
  "The front page: the commercial apps, the open-source projects, then
  feedback and support. `projects` carry their icons (project-icons)."
  [site projects apps year]
  (let [style (css apps projects)
        {os :open-source com :commercial fb :feedback} site]
    (str
     "<!doctype html>\n"
     (h/html
      {:mode :html}
      [:html {:lang "en"}
       (head site style (:title site) (str (:lead site) " Each app's support page has its contacts and its privacy policy."))
       [:body
        [:div.page
         (header site [:nav.jump {:aria-label "On this page"}
                       [:a {:href "#apps"} "Apps"]
                       [:a {:href "#open-source"} "Open source"]
                       [:a {:href "#feedback"} "Feedback & support"]])
         [:main
          [:section#apps {:aria-labelledby "apps-title"}
           [:h2#apps-title (:title com)]
           [:p.section-lead (:lead com)]
           [:div.apps (map app-card apps)]
           [:aside.next {:aria-label "Coming later"}
            [:span.chip "Later"]
            [:p (:note com)]]]
          [:section#open-source {:aria-labelledby "open-source-title"}
           [:h2#open-source-title (:title os)]
           [:p.section-lead (:lead os)]
           [:div.projects (map project-card projects)]]
          [:section#feedback {:aria-labelledby "feedback-title"}
           [:h2#feedback-title (:title fb)]
           [:p.section-lead (:lead fb)]
           [:div.channels
            (for [{:keys [badge title about action] :as ch} (:channels fb)]
              [:article.channel
               [:span.chip badge]
               [:h3 title]
               [:p about]
               [:a.button {:href (channel-url (:repo fb) ch)} action [:span {:aria-hidden "true"} "→"]]])]
           [:p.fine (:note fb) " " [:a {:href (str (:repo fb) "/discussions")} "Browse the discussions"] "."]
           [:div.help
            [:div.help-item
             [:h3 "Business, privacy and legal"]
             [:p "Partnerships, licensing, questions about a privacy policy or your data, and legal notices: write to us privately."]
             [:p [:a.go {:href (mailto (:business site) "Clogem")} (:business site)]]]]]]
         (footer year)]]]))))

(defn not-found-page
  "The 404 page GitHub Pages shows for any address in the hub that isn't
  there. Its links are absolute: it is served at the missing address."
  [site apps year]
  (let [style (css apps [])]
    (str
     "<!doctype html>\n"
     (h/html
      {:mode :html}
      [:html {:lang "en"}
       (head site style (str "Page not found: " (:title site)) "This page isn't part of Clogem Support.")
       [:body
        [:div.page
         (header site)
         [:main
          [:section.notfound {:aria-labelledby "nf-title"}
           [:h2#nf-title "Page not found"]
           [:p "There's no page at this address."]
           [:nav.links {:aria-label "Where to go"}
            [:a.primary {:href (:url site)} "Clogem Support"]
            (for [{:keys [folder name]} apps]
              [:a {:href (str (:url site) folder "/")} name])]]]
         (footer year)]]]))))

(defn -main [& args]
  (let [valued #{"--out" "--site-dir" "--config" "--root"}
        opts   (loop [[a & more :as as] args, out {}]
                 (cond (empty? as) out
                       (valued a)  (if (seq more) (recur (rest more) (assoc out a (first more)))
                                       (fail! a "needs a value"))
                       :else       (fail! "bb portal [--site-dir DIR] [--out DIR], not" a)))
        site   (edn/read-string (slurp (get opts "--config" "site/site.edn")))
        _      (when-let [problems (seq (check-site site))]
                 (fail! (str "site/site.edn isn't right:\n  " (str/join "\n  " problems))))
        dir    (get opts "--site-dir")
        _      (when (and dir (not (fs/directory? dir))) (fail! dir "isn't a folder"))
        projects (project-icons (get-in site [:open-source :projects]) (get opts "--root" "."))
        apps   (vec (published-apps (:apps site) dir))
        _      (when (empty? apps) (fail! "No app is published yet: nothing to list."))
        out    (get opts "--out" "_site")
        year   (str (java.time.Year/now))]
    (fs/create-dirs out)
    (spit (fs/file out "index.html") (index-page site projects apps year))
    (spit (fs/file out "404.html") (not-found-page site apps year))
    (spit (fs/file out ".nojekyll") "")
    (println (str "Wrote " out "/index.html, 404.html and .nojekyll, listing "
                  (str/join ", " (concat (map :name projects) (map :name apps))) ", for " (:url site)))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
