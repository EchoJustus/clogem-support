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
  root files and the language folders alone and leaves the apps' folders as
  they are.

  In five languages (owner, 2026-10-25): English at the root, from
  site.edn, and one folder per other language (zh-hant/, zh-hans/, pt/,
  es/), from site/i18n/<id>.edn, which must give every word site.edn
  gives (--i18n DIR reads them elsewhere). Without a script nothing picks
  the visitor's language: each page links to the others from a dropdown
  (a <details>), and tells search engines about them (hreflang).

  The front page has three parts: the commercial apps (each with where to
  get it, its page and its policy), the open-source projects (each with
  its icon from icons/ and a link to its source), and feedback and support
  in the open, through this repository's GitHub Discussions and issue
  forms (.github/). The apps come first (owner, 2026-10-05).

  The English and Chinese pages set their text in Clogem Kai, subsets of
  LXGW WenKai from fonts/ (bb fonts); Portuguese and Spanish keep the
  sans (owner, 2026-10-26).

  Like the apps' pages: one self-contained file each, no JavaScript, nothing
  loaded from another site, and a Content-Security-Policy that allows only
  the page's own style (by its SHA-256), data: images and data: fonts. An app's icon is
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
                    "its :status must be text")
                  (when (and (some? (:page project)) (not (and (string? (:page project)) (re-matches one-name (:page project)))))
                    "its :page must be one lowercase name (its folder on gh-pages)")]
         :when problem]
     (str "project " (pr-str (or id project)) ": " problem))))

(defn- check-apps [commercial apps language-ids]
  (concat
   (when-not (every? text? (map commercial [:title :lead :note]))
     [":commercial needs :title, :lead and :note"])
   (when-not (seq apps) [":apps must list at least one app"])
   (when (and (seq apps) (not (apply distinct? (map :folder apps))))
     [":apps must each have their own :folder"])
   (for [{:keys [folder name subtitle about accent store] :as app} apps
         problem [(when-not (and (string? folder) (re-matches one-name folder))
                    "its :folder must be one lowercase folder name")
                  (when (contains? (set language-ids) folder)
                    "its :folder is a language's: the front page publishes that one")
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

(def ^:private language-tag #"[a-z]{2,3}(?:-[A-Za-z0-9]{2,8})*")

(defn- check-languages [languages]
  (concat
   (when-not (seq languages) [":languages must list at least one language"])
   (when (and (seq languages) (not (apply distinct? (map :id languages))))
     [":languages must each have their own :id"])
   (when (and (seq languages) (not= "en" (:id (first languages))))
     [":languages starts with en: the root page, in site.edn's words"])
   (for [{:keys [id lang hreflang name] :as language} languages
         problem [(when-not (and (string? id) (re-matches one-name id))
                    "its :id must be one lowercase name (its folder)")
                  (when-not (and (string? lang) (re-matches language-tag lang))
                    "its :lang must be a language tag, like pt-BR")
                  (when-not (and (string? hreflang) (re-matches language-tag hreflang))
                    "its :hreflang must be a language tag, like pt")
                  (when-not (text? name)
                    "it needs its :name, in its own language")
                  (when-not (contains? #{nil true false} (:kai language))
                    "its :kai must be true or false")]
         :when problem]
     (str "language " (pr-str (or id language)) ": " problem))))

(def ui-keys
  "The page's own words, besides the sections' (site.edn's :ui). Some are
  templates: {app}, {store}, {name} and {license} are filled in."
  [:heading :description-tail :on-this-page :jump-apps :jump-open-source :jump-feedback
   :language :theme :theme-auto :theme-auto-title :theme-light :theme-light-title
   :theme-dark :theme-dark-title :later :later-label :open-source-tag :links-label :source :project-page
   :get-label :download-from :features :privacy :feedback :in-english :browse
   :business-title :business-about :footer-privacy
   :not-found-title :not-found-text :not-found-where :not-found-description])

(defn- check-ui [ui]
  (concat
   (when-not (and (map? ui) (= (set ui-keys) (set (keys ui))))
     [(str ":ui must give exactly these words: " (str/join " " ui-keys))])
   ;; :in-english marks a link to a page in English: empty in English itself
   (for [k ui-keys :when (and (map? ui) (not= k :in-english) (not (text? (get ui k))))]
     (str ":ui " k " must be text"))))

(defn check-site
  "site.edn's problems, in words."
  [{:keys [url title lead business open-source commercial apps feedback languages ui]}]
  (concat
   (for [[k v] {:url url :title title :lead lead :business business}
         :when (not (text? v))]
     (str k " must be text"))
   (when-not (and (string? url) (str/starts-with? url "https://") (str/ends-with? url "/"))
     [":url must be https and end with /"])
   (when-not (and (string? business) (re-matches #"[^\s@<>\"]+@[^\s@<>\"]+\.[A-Za-z]{2,}" business))
     [":business must be an email address"])
   (check-languages languages)
   (check-ui ui)
   (check-projects open-source)
   (check-apps commercial apps (map :id languages))
   (for [{:keys [id page]} (:projects open-source)
         :when (and page (or (contains? (set (map :id languages)) page) (contains? (set (map :folder apps)) page)))]
     (str "project " (pr-str id) ": its :page is a language's or an app's folder"))
   (check-feedback feedback)))

;; ---------------------------------------------------------------------------
;; The languages

(defn- channel-key
  "A feedback channel's name in a translation: its category, or its form."
  [{:keys [discussion issue-form]}]
  (or discussion issue-form))

(defn words
  "The words a translation gives, in the shape of site/i18n/<id>.edn: the
  page's own (:ui) and the sections', each project, app and channel keyed
  by its :id, :folder or channel-key. Names stay as they are: the apps',
  the projects', the stores', and an app's subtitle, which is part of its
  product name (Wmark Pro: Video Watermarker, the same in every Store
  listing)."
  [{:keys [title lead ui open-source commercial apps feedback]}]
  {:title title
   :lead  lead
   :ui    ui
   :open-source {:title    (:title open-source)
                 :lead     (:lead open-source)
                 :projects (into {} (for [p (:projects open-source)]
                                      [(:id p) (select-keys p [:subtitle :about :status])]))}
   :commercial (select-keys commercial [:title :lead :note])
   :apps (into {} (for [{:keys [folder about store]} apps]
                    [folder (cond-> {:about about} store (assoc :store {:note (:note store)}))]))
   :feedback {:title    (:title feedback)
              :lead     (:lead feedback)
              :note     (:note feedback)
              :channels (into {} (for [c (:channels feedback)]
                                   [(channel-key c) (select-keys c [:badge :title :about :action])]))}})

(defn- leaves
  "Every path to a value that isn't a map."
  ([m] (leaves [] m))
  ([path m] (if (map? m) (mapcat (fn [[k v]] (leaves (conj path k) v)) m) [path])))

(defn- placeholders [s] (set (re-seq #"\{[a-z]+\}" (str s))))

(defn translation-problems
  "Why the translation `tr` (read from `file`) can't be used, in words: it
  must give every word site.edn gives (english, from `words`), nothing
  else, each as text, with the same placeholders."
  [english tr file]
  (if-not (map? tr)
    [(str file ": must be a map, in site.edn's shape")]
    (let [want (set (leaves english))
          have (set (leaves tr))]
      (concat
       (for [path (sort-by pr-str (remove have want))] (str file ": missing " (pr-str path)))
       (for [path (sort-by pr-str (remove want have))] (str file ": not in site.edn " (pr-str path)))
       (for [path (sort-by pr-str (filter want have))
             :let [v (get-in tr path) e (get-in english path)]
             problem [(when-not (text? v) "must be text")
                      (when (not= (placeholders e) (placeholders v))
                        (str "must keep the placeholders " (str/join " " (sort (placeholders e)))))]
             :when problem]
         (str file ": " (pr-str path) " " problem))))))

(defn localize
  "`site` in the words of the translation `tr`."
  [site tr]
  (-> site
      (merge (select-keys tr [:title :lead :ui]))
      (update :open-source merge (select-keys (:open-source tr) [:title :lead]))
      (update-in [:open-source :projects]
                 (fn [ps] (mapv #(merge % (get-in tr [:open-source :projects (:id %)])) ps)))
      (update :commercial merge (:commercial tr))
      (update :apps (fn [apps]
                      (mapv (fn [app]
                              (let [t (get-in tr [:apps (:folder app)])]
                                (cond-> (merge app (dissoc t :store))
                                  (:store t) (update :store merge (:store t)))))
                            apps)))
      (update :feedback merge (select-keys (:feedback tr) [:title :lead :note]))
      (update-in [:feedback :channels]
                 (fn [cs] (mapv #(merge % (get-in tr [:feedback :channels (channel-key %)])) cs)))))

(defn- fill
  "A :ui template with its {placeholders} filled from `values`."
  [template values]
  (str/replace template #"\{([a-z]+)\}" (fn [[_ k]] (str (get values (keyword k))))))

(defn- then
  "Two sentences in a row: a space between them, but none after a full
  stop that takes no space (Chinese 。)."
  [a b]
  (str a (when-not (re-find #"[。！？：]$" a) " ") b))

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

(defn language-folders
  "The other `languages` a page in `folder` of `site-dir` is in, by :id: the
  subfolder holding that language's index.html, named by the hub's :id
  (wmark-pro/zh-hant/) or by a language tag, its :hreflang or its :lang
  (clogem-press/zh-Hant/, clogem-press/pt-BR/)."
  [site-dir folder languages]
  (into {} (for [{:keys [id hreflang lang]} (rest languages)
                 :let [sub (some #(when (fs/exists? (fs/file site-dir folder % "index.html")) %)
                                 (distinct [id hreflang lang]))]
                 :when sub]
             [id sub])))

(defn published-apps
  "The apps to list: with `site-dir` (a checkout of gh-pages), those whose
  folder holds an index.html, each with its icon and the other `languages`
  its page is in (:languages, from language-folders; Wmark Pro's since
  2026-10-26); without, all of them, with no icons and their pages in
  English (a preview)."
  [apps site-dir languages]
  (if-not site-dir
    apps
    (keep (fn [{:keys [folder] :as app}]
            (let [page (fs/file site-dir folder "index.html")]
              (if (fs/exists? page)
                (let [icon (app-icon (slurp page))]
                  (when-not icon (warn! folder "has no icon in its page; its card shows a plain mark"))
                  (assoc app :icon icon :languages (language-folders site-dir folder languages)))
                (do (warn! folder "isn't published yet (no" (str page) "); it isn't listed") nil))))
          apps)))

(defn published-pages
  "The projects, each with a :page (its folder on gh-pages) linked once it
  is published: with `site-dir`, a project whose page isn't there yet
  loses its :page (its card links its source alone), and one that is gains
  the other `languages` it is in (:languages, from language-folders);
  without, every :page is kept, in English (a preview)."
  [projects site-dir languages]
  (if-not site-dir
    projects
    (mapv (fn [{:keys [id page] :as project}]
            (cond
              (nil? page) project
              (fs/exists? (fs/file site-dir page "index.html"))
              (assoc project :languages (language-folders site-dir page languages))
              :else
              (do (warn! "project" id "has no page on gh-pages yet (no" (str (fs/file site-dir page "index.html")) "); its card links its source alone")
                  (dissoc project :page))))
          projects)))

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

(def ^:private latin-text
  "\"Segoe UI Variable Text\",\"Segoe UI\",system-ui,-apple-system,BlinkMacSystemFont,Roboto,\"Helvetica Neue\",Arial")

(def ^:private latin-display
  "\"Segoe UI Variable Display\",\"Segoe UI\",system-ui,-apple-system,BlinkMacSystemFont,Roboto")

(def kai-family
  "The text face of the English and Chinese pages: subsets of LXGW WenKai
  made by bb fonts. The font's licence reserves its names for unmodified
  fonts, so the subsets go by a name of our own."
  "Clogem Kai")

(def ^:private text-stack
  "Every text in Clogem Kai, a glyph it lacks in the sans, then in the
  page's Chinese fonts (--cjk)."
  (str "\"" kai-family "\"," latin-text ",var(--cjk)"))

(def ^:private sans-stack (str latin-text ",var(--cjk)"))

(def ^:private display-stack (str latin-display ",var(--cjk)"))

(defn- font-faces
  "Clogem Kai's two faces for one page, as @font-face rules with data:
  URIs: the regular, and the bold that its semibold labels take."
  [{:keys [regular bold]}]
  (for [[weight b64] [[400 regular] [700 bold]] :when b64]
    (str "@font-face{font-family:\"" kai-family "\";src:url(data:font/woff2;base64," b64 ") format(\"woff2\");"
         "font-weight:" weight ";font-style:normal;font-display:swap}")))

(defn- lang-selector
  "Elements whose own language is one of `langs` (:lang tags), the root
  aside."
  [langs]
  (str/join "," (for [l (sort langs)] (str "[lang=\"" l "\"]:not(html)"))))

(defn css
  "The page's styles, one accent rule per app and project (inline style
  attributes would need the CSP to allow them), with the page's subsets of
  Clogem Kai (`fonts`, from read-fonts; nil on a page in the sans).

  The theme follows the system, or the switcher in the header: three radio
  buttons, read by :has() on the root, so it takes no script. Without a
  script or a cookie the choice isn't remembered: each page opens on
  Auto.

  On the English and Chinese pages the text is set in Clogem Kai (owner,
  2026-10-26), but for headings and heavy labels (weight 700: h1 to h3,
  .eyebrow, .chip, the store's name), which keep the sans. Semibold labels
  (600 and 650: buttons, links, the switcher, the language menu) take
  Kai's bold. Portuguese and Spanish keep the sans everywhere: their pages,
  and their words on the others (`sans-langs`: the menu's names, the 404
  page's lines). page-text mirrors this."
  [apps projects fonts sans-langs]
  (str/join
   "\n"
   (concat
    (font-faces fonts)
    (for [t colour-tokens]
      (str "@property " t "{syntax:\"<color>\";inherits:true;initial-value:transparent}"))
    [(str ":root{color-scheme:light dark;" light ";"
          "transition:" (str/join "," (map #(str % " .35s ease") colour-tokens)) "}")
     (str "@media (prefers-color-scheme:dark){:root:not(:has(#theme-light:checked)){color-scheme:dark;" dark "}}")
     (str ":root:has(#theme-dark:checked){color-scheme:dark;" dark "}")
     ":root:has(#theme-light:checked){color-scheme:light}"
     ;; Chinese in its own fonts, Simplified and Traditional (never the
     ;; Japanese forms a system might fall back to), after the Latin face
     ":root{--cjk:sans-serif}"
     ":lang(zh-Hans){--cjk:\"Microsoft YaHei UI\",\"Microsoft YaHei\",\"PingFang SC\",\"Hiragino Sans GB\",\"Noto Sans CJK SC\",\"Source Han Sans SC\",sans-serif}"
     ":lang(zh-Hant){--cjk:\"Microsoft JhengHei UI\",\"Microsoft JhengHei\",\"PingFang TC\",\"Noto Sans CJK TC\",\"Source Han Sans TC\",sans-serif}"
     (str "*,*::before{box-sizing:border-box}"
          "html{-webkit-text-size-adjust:100%;text-size-adjust:100%}")
     (str "body{margin:0;min-height:100vh;color:var(--text);background:"
          "radial-gradient(1100px 560px at 8% -12%,var(--glow-1),transparent 62%),"
          "radial-gradient(900px 520px at 100% -4%,var(--glow-2),transparent 58%),var(--bg);"
          "font:400 1.0625rem/1.7 " (if fonts text-stack sans-stack) ";"
          "-webkit-font-smoothing:antialiased}")
     (str "[lang]:not(html){font-family:" (if fonts text-stack sans-stack) "}")
     ".page{max-width:62rem;margin:0 auto;padding:clamp(2.5rem,7vw,5.5rem) 1.25rem 2.5rem}"
     "a{color:var(--link);text-underline-offset:.18em}"
     "a:focus-visible{outline:3px solid var(--focus);outline-offset:3px;border-radius:.45rem}"
     (str "h1,h2,h3{font-family:" display-stack ";color:var(--heading)}")
     (str ".eyebrow,.chip,.store strong{font-family:" sans-stack "}")
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
     ".controls{display:flex;flex-wrap:wrap;align-items:center;justify-content:flex-end;gap:.6rem;margin-left:auto}"
     ;; the language menu: a <details>, so it opens without a script
     ".langs{position:relative}"
     ".sr{position:absolute;width:1px;height:1px;overflow:hidden;clip-path:inset(50%);white-space:nowrap}"
     (str ".langs summary{display:inline-flex;align-items:center;gap:.45rem;padding:.48rem .9rem;border:1px solid var(--border);"
          "border-radius:999px;background:var(--surface);box-shadow:var(--shadow);font-size:.86rem;font-weight:650;"
          "color:var(--text);cursor:pointer;list-style:none;user-select:none}")
     ".langs summary::-webkit-details-marker{display:none}"
     ".langs summary:hover{border-color:var(--focus)}"
     ".langs summary:focus-visible{outline:3px solid var(--focus);outline-offset:2px}"
     ".langs svg{flex:none;width:1rem;height:1rem}"
     ".langs .chevron{width:.8rem;height:.8rem;transition:transform .2s ease}"
     ".langs[open] .chevron{transform:rotate(180deg)}"
     (str ".langs .menu{position:absolute;right:0;top:calc(100% + .45rem);z-index:10;display:grid;gap:2px;min-width:12rem;"
          "padding:.35rem;background:var(--surface);border:1px solid var(--border);border-radius:1rem;box-shadow:var(--shadow)}")
     (str ".langs .menu a{display:flex;align-items:center;justify-content:space-between;gap:1rem;padding:.5rem .8rem;"
          "border-radius:.7rem;color:var(--text);font-size:.95rem;text-decoration:none}")
     ".langs .menu a:hover{background:var(--surface-2)}"
     ".langs .menu a[aria-current]{background:var(--chip);color:var(--chip-ink);font-weight:650}"
     ".brand{display:flex;align-items:center;gap:clamp(.9rem,2.5vw,1.25rem)}"
     ".mark{flex:none;width:clamp(3.25rem,8vw,4.25rem);height:clamp(3.25rem,8vw,4.25rem)}"
     ".mark svg{display:block;width:100%;height:100%}"
     ".eyebrow{margin:0 0 .2rem;font-size:.78rem;font-weight:700;letter-spacing:.16em;text-transform:uppercase;color:var(--chip-ink)}"
     "h1{margin:0;font-size:clamp(2.2rem,5.2vw,3.3rem);line-height:1.05;letter-spacing:-.025em;font-weight:700}"
     ":lang(zh) :is(h1,h2){letter-spacing:0}"
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
     ".elsewhere{display:grid;gap:.45rem;margin-top:1.9rem}"
     ".notfound .elsewhere p{margin:0;font-size:1rem}"
     (str "footer{display:flex;flex-wrap:wrap;justify-content:space-between;gap:.4rem 1.5rem;margin-top:clamp(3.5rem,9vw,5.5rem);"
          "padding-top:1.4rem;border-top:1px solid var(--border);font-size:.88rem;color:var(--muted)}")
     "footer p{margin:0}"
     "@media (prefers-reduced-motion:reduce){:root,.theme label,.store,.button span,.langs .chevron{transition:none}.store:hover,.button:hover span{transform:none}}"
     (str "@media print{:root{--bg:#fff;--surface:#fff;--surface-2:#fff;--text:#000;--muted:#333;--heading:#000;--link:#000;--border:#bbb;--shadow:none}"
          "body{background:#fff}.app::before,.project::before,.jump,.theme,.langs{display:none}}")]
    (for [{:keys [folder accent]} apps]
      (format ".app-%s::before{background:linear-gradient(90deg,%s,%s)}" folder (first accent) (second accent)))
    (for [{:keys [id accent]} projects]
      (format ".project-%s::before{background:linear-gradient(90deg,%s,%s)}" id (first accent) (second accent)))
    ;; Portuguese and Spanish words on a page in Kai: in the sans, after
    ;; [lang]:not(html), which they would otherwise take
    (when (and fonts (seq sans-langs))
      [(str (lang-selector sans-langs) "{font-family:" sans-stack "}")]))))

(defn csp
  "Nothing but the page's own style (by the SHA-256 of `style`), data:
  images (the favicon and the apps' and projects' icons) and data: fonts
  (its subsets of Clogem Kai)."
  [style]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256") (.getBytes ^String style StandardCharsets/UTF_8))]
    (str "default-src 'none'; style-src 'sha256-" (.encodeToString (Base64/getEncoder) digest) "'; "
         "img-src data:; font-src data:; base-uri 'none'; form-action 'none'")))

;; ---------------------------------------------------------------------------
;; The pages
;;
;; Each page is written for one language of site.edn's :languages, from a
;; folder: "" for the root (the first language, English), "<id>/" for the
;; others. `base` is how its links reach the hub's root: "" from the root,
;; "../" from a language's folder, and the hub's own address from the 404
;; page, which is served at whatever address was missing.

(defn- folder
  "Where a language's page is, from the hub's root: the root for the first
  language, its own folder for the others."
  [languages {:keys [id]}]
  (if (= id (:id (first languages))) "" (str id "/")))

(defn- href
  "A link from a page whose root is `base` to `path` at the root."
  [base path]
  (let [h (str base path)] (if (= "" h) "./" h)))

(defn- head [site style title description languages current]
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
   (when current
     (list
      [:link {:rel "canonical" :href (str (:url site) (folder languages current))}]
      ;; the same page in the other languages, for search engines; the
      ;; root's English for anyone else
      (for [l languages]
        [:link {:rel "alternate" :hreflang (:hreflang l) :href (str (:url site) (folder languages l))}])
      [:link {:rel "alternate" :hreflang "x-default" :href (:url site)}]))
   [:link {:rel "icon" :type "image/svg+xml" :href (data-uri (hub-mark))}]
   [:style (h/raw style)]])

(def ^:private theme-icons
  "The switcher's icons (our own drawings, in the text's colour)."
  {"system" "<svg viewBox=\"0 0 16 16\" aria-hidden=\"true\" focusable=\"false\"><circle cx=\"8\" cy=\"8\" r=\"6.25\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.5\"/><path d=\"M8 1.75a6.25 6.25 0 0 1 0 12.5Z\" fill=\"currentColor\"/></svg>"
   "light"  "<svg viewBox=\"0 0 16 16\" aria-hidden=\"true\" focusable=\"false\"><circle cx=\"8\" cy=\"8\" r=\"3\" fill=\"currentColor\"/><path d=\"M8 1v1.6M8 13.4V15M1 8h1.6M13.4 8H15M3.05 3.05l1.13 1.13M11.82 11.82l1.13 1.13M3.05 12.95l1.13-1.13M11.82 4.18l1.13-1.13\" stroke=\"currentColor\" stroke-width=\"1.5\" stroke-linecap=\"round\"/></svg>"
   "dark"   "<svg viewBox=\"0 0 16 16\" aria-hidden=\"true\" focusable=\"false\"><path d=\"M13.5 10.3A6 6 0 0 1 5.7 2.5a6 6 0 1 0 7.8 7.8Z\" fill=\"currentColor\"/></svg>"})

(defn- theme-switcher
  "Auto, Light or Dark, as radio buttons the stylesheet reads (no script)."
  [ui]
  [:fieldset.theme
   [:legend (:theme ui)]
   (for [[id label title] [["system" (:theme-auto ui) (:theme-auto-title ui)]
                           ["light" (:theme-light ui) (:theme-light-title ui)]
                           ["dark" (:theme-dark ui) (:theme-dark-title ui)]]]
     (list [:input (cond-> {:type "radio" :name "theme" :id (str "theme-" id) :value id}
                     (= id "system") (assoc :checked true))]
           [:label {:for (str "theme-" id) :title title} (h/raw (theme-icons id)) label]))])

(def ^:private globe
  (str "<svg viewBox=\"0 0 16 16\" aria-hidden=\"true\" focusable=\"false\"><g fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.3\">"
       "<circle cx=\"8\" cy=\"8\" r=\"6.35\"/><path d=\"M1.9 8h12.2M8 1.65c1.75 1.9 2.6 4 2.6 6.35S9.75 12.45 8 14.35C6.25 12.45 5.4 10.35 5.4 8S6.25 3.55 8 1.65Z\"/>"
       "</g></svg>"))

(def ^:private chevron
  "<svg class=\"chevron\" viewBox=\"0 0 12 12\" aria-hidden=\"true\" focusable=\"false\"><path d=\"M2.5 4.5 6 8l3.5-3.5\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.5\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/></svg>")

(def ^:private check
  "<svg viewBox=\"0 0 16 16\" aria-hidden=\"true\" focusable=\"false\"><path d=\"m3.5 8.5 3 3 6-7\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/></svg>")

(defn- language-menu
  "The other languages, from a dropdown (owner, 2026-10-25: a row of five
  names is too many). A <details>, so it opens without a script; each
  language is named in its own words, the current one checked."
  [ui languages current base]
  [:details.langs
   ;; read aloud as "Language" and the current one, in no punctuation of
   ;; ours
   [:summary (h/raw globe) [:span.sr (:language ui)] [:span {:lang (:lang current)} (:name current)] (h/raw chevron)]
   [:nav.menu {:aria-label (:language ui)}
    (for [l languages]
      [:a (cond-> {:href (href base (folder languages l)) :lang (:lang l) :hreflang (:hreflang l)}
            (= (:id l) (:id current)) (assoc :aria-current "page"))
       (:name l)
       (when (= (:id l) (:id current)) (h/raw check))])]])

(defn- header [site languages current base & [jump]]
  (let [ui (:ui site)]
    [:header.hero
     [:div.top
      [:div.brand
       [:span.mark {:aria-hidden "true"} (h/raw (hub-mark))]
       [:div
        [:p.eyebrow "Clogem"]
        [:h1 (:heading ui)]]]
      [:div.controls
       (theme-switcher ui)
       (language-menu ui languages current base)]]
     [:div.rule]
     [:p.lead (:lead site)]
     jump]))

(def copyright-holder
  "The publisher in every footer's copyright notice, in full, never
  shortened or split (CLAUDE.md, \"Footer copyright notices\", owner,
  2026-10-26). The hub's footer names the publisher alone."
  "EchoJustus (Jiapeng Ji)")

(defn- footer [ui year]
  [:footer
   [:p (str "© " year " " copyright-holder ".")]
   [:p (:footer-privacy ui)]])

(defn- mailto [email subject]
  (str "mailto:" email "?subject=" (str/replace (URLEncoder/encode ^String subject "UTF-8") "+" "%20")))

(defn- page-in
  "The path of the page in `folder`, in `current`'s language when it has
  one (`languages`, from language-folders), else the folder's root (in
  English); and whether it is in `current`'s own language."
  [folder languages current english?]
  (let [sub (when-not english? (get languages (:id current)))]
    [(str folder "/" (when sub (str sub "/"))) (boolean (or english? sub))]))

(defn- project-card
  "A project's card: its source, and its own page on this site when it has
  one (published-pages' :page), in `current`'s language when the page is
  in it, else in English, marked as an app's is."
  [ui base english? current {:keys [id name subtitle about icon-uri repo license status page languages]}]
  (let [[path own?] (when page (page-in page languages current english?))
        page-link   (when page
                      [:a.primary (cond-> {:href (href base path)} (not own?) (assoc :hreflang "en"))
                       (str (:project-page ui) (if own? "" (:in-english ui)))])
        links       (when (or page repo)
                      [:nav.links {:aria-label (fill (:links-label ui) {:name name})}
                       page-link
                       (when repo [(if page :a :a.primary) {:href repo} (:source ui)])])]
    [:article {:class (str "project project-" id)}
     [:img.app-icon {:src icon-uri :alt "" :width "56" :height "56"}]
     [:h3 name]
     [:p.sub subtitle]
     [:p.about about]
     (if repo
       (list [:span.tag (fill (:open-source-tag ui) {:license license})] links)
       (list [:p.status status] links))]))

(defn- app-card
  "An app's card, its links to its page in `current`'s language when it
  has one (published-apps' :languages), else in English: from a page in
  another language, those say so (:in-english) and carry hreflang."
  [ui base english? current {:keys [folder name subtitle about icon store languages]}]
  (let [[page own?] (page-in folder languages current english?)
        to-english (fn [attrs] (cond-> attrs (not own?) (assoc :hreflang "en")))
        marker     (if own? "" (:in-english ui))]
    [:article {:class (str "app app-" folder)}
     (if icon
       [:img.app-icon {:src icon :alt "" :width "64" :height "64"}]
       [:span.app-icon.plain {:aria-hidden "true"}])
     [:h3 name]
     [:p.sub subtitle]
     [:p.about about]
     (when store
       [:div.get
        [:a.store {:href (:url store) :aria-label (fill (:get-label ui) {:app name :store (:name store)})}
         (h/raw bag)
         [:span [:small (:download-from ui)] [:strong (:name store)]]]
        [:span.store-note (:note store)]])
     [:nav.links {:aria-label (fill (:links-label ui) {:name name})}
      [:a.primary (to-english {:href (href base page)}) (str (:features ui) marker)]
      [:a (to-english {:href (href base (str page "#privacy"))}) (str (:privacy ui) marker)]
      [:a {:href "#feedback"} (:feedback ui)]]]))

(defn index-body
  "The front page's <body> in the language `current`, from `site` in its
  words (see localize): the commercial apps, the open-source projects,
  then feedback and support. Its projects carry their icons
  (project-icons), its apps theirs (published-apps)."
  [site year languages current]
  (let [projects (get-in site [:open-source :projects])
        apps     (:apps site)
        ui       (:ui site)
        english? (= (:id current) (:id (first languages)))
        base     (if english? "" "../")
        {os :open-source com :commercial fb :feedback} site]
    [:body
     [:div.page
      (header site languages current base
              [:nav.jump {:aria-label (:on-this-page ui)}
               [:a {:href "#apps"} (:jump-apps ui)]
               [:a {:href "#open-source"} (:jump-open-source ui)]
               [:a {:href "#feedback"} (:jump-feedback ui)]])
      [:main
       [:section#apps {:aria-labelledby "apps-title"}
        [:h2#apps-title (:title com)]
        [:p.section-lead (:lead com)]
        [:div.apps (map (partial app-card ui base english? current) apps)]
        [:aside.next {:aria-label (:later-label ui)}
         [:span.chip (:later ui)]
         [:p (:note com)]]]
       [:section#open-source {:aria-labelledby "open-source-title"}
        [:h2#open-source-title (:title os)]
        [:p.section-lead (:lead os)]
        [:div.projects (map (partial project-card ui base english? current) projects)]]
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
        [:p.fine (then (:note fb) "") [:a {:href (str (:repo fb) "/discussions")} (:browse ui)]]
        [:div.help
         [:div.help-item
          [:h3 (:business-title ui)]
          [:p (:business-about ui)]
          [:p [:a.go {:href (mailto (:business site) "Clogem")} (:business site)]]]]]]
      (footer ui year)]]))

(defn not-found-body
  "The <body> of the 404 page GitHub Pages shows for any address in the
  hub that isn't there, in English, with a line in each other language.
  Its links are absolute: it is served at the missing address. `sites`
  are site.edn in each language's words, by :id."
  [sites year languages]
  (let [english (first languages)
        site    (sites (:id english))
        ui      (:ui site)
        base    (:url site)]
    [:body
     [:div.page
      (header site languages english base)
      [:main
       [:section.notfound {:aria-labelledby "nf-title"}
        [:h2#nf-title (:not-found-title ui)]
        [:p (:not-found-text ui)]
        [:nav.links {:aria-label (:not-found-where ui)}
         [:a.primary {:href base} (:title site)]
         (for [{:keys [folder name]} (:apps site)]
           [:a {:href (str base folder "/")} name])
         (for [{:keys [page name]} (get-in site [:open-source :projects]) :when page]
           [:a {:href (str base page "/")} name])]
        [:div.elsewhere
         (for [l (rest languages)
               :let [s (sites (:id l))]]
           [:p {:lang (:lang l)}
            (then (get-in s [:ui :not-found-text]) "")
            [:a {:href (str base (folder languages l)) :hreflang (:hreflang l)} (:title s)]])]]]
      (footer ui year)]]))

(defn pages
  "Every page the front page publishes, by key (a language's :id, or
  \"404\"): what its <head> needs and its <body>, whether its text is in
  Clogem Kai (:kai?, its language's :kai; the 404 page's is English's), and
  the languages whose words stay in the sans wherever they appear
  (:sans-langs, their :lang tags). `sites` are site.edn in each language's
  words, by :id."
  [sites year languages]
  (let [english (first languages)
        site    (sites (:id english))
        ui      (:ui site)
        sans    (set (map :lang (remove :kai languages)))]
    (into {"404" {:site site :lang english :current nil :file "404.html"
                  :title (str (:not-found-title ui) ": " (:title site))
                  :description (:not-found-description ui)
                  :apps (:apps site) :projects []
                  :kai? (boolean (:kai english)) :sans-langs sans
                  :body (not-found-body sites year languages)}}
          (for [l languages
                :let [s (sites (:id l))]]
            [(:id l) {:site s :lang l :current l :file (str (folder languages l) "index.html")
                      :title (:title s)
                      :description (then (:lead s) (get-in s [:ui :description-tail]))
                      :apps (:apps s) :projects (get-in s [:open-source :projects])
                      :kai? (boolean (:kai l)) :sans-langs sans
                      :body (index-body s year languages l)}]))))

(defn page-html
  "A page from `pages`, with its subsets of Clogem Kai (`fonts`; nil on a
  page in the sans)."
  [languages {:keys [site lang current title description apps projects sans-langs body]} fonts]
  (let [style (css apps projects fonts sans-langs)]
    (str
     "<!doctype html>\n"
     (h/html
      {:mode :html}
      [:html {:lang (:lang lang)}
       (head site style title description languages current)
       body]))))

;; ---------------------------------------------------------------------------
;; The text, by the face it is set in (for bb fonts and the tests)

(def ^:private heavy-classes
  "Classes set at weight 700, in the sans: no subset needed."
  #{"eyebrow" "chip"})

(def ^:private semibold-classes
  "Classes whose text is set at 600 or 650: Clogem Kai's bold. (The
  switcher's labels, the links and buttons, the licence tag, the business
  address.)"
  #{"jump" "links" "button" "theme" "tag" "go"})

(defn- tag-parts
  "A hiccup tag's name and its classes: :a.primary -> [:a #{\"primary\"}]."
  [kw]
  (let [[_ tag more] (re-matches #"([a-z0-9]+)(.*)" (name kw))]
    [(keyword tag) (set (map second (re-seq #"\.([A-Za-z0-9_-]+)" more)))]))

(defn- weight-of
  "The face an element's text is set in, as the stylesheet sets it: :sans
  (headings, heavy labels, and words in a language of `sans-langs`), :bold
  or :regular (Clogem Kai's), else its parent's."
  [sans-langs tag classes attrs inherited]
  (cond (or (#{:h1 :h2 :h3 :strong} tag) (some heavy-classes classes)
            (contains? sans-langs (:lang attrs))) :sans
        (= :sans inherited) :sans
        (or (#{:summary :small} tag) (some semibold-classes classes)
            (and (= :a tag) (= "page" (:aria-current attrs)))) :bold
        :else inherited))

(defn page-text
  "The text of a page's hiccup `body`, by the face it is set in: :regular
  and :bold (Clogem Kai's, which bb fonts subsets) and :sans. Markup that
  isn't text (raw SVG, attributes) is left out."
  [{:keys [body sans-langs]}]
  (letfn [(walk [acc node w]
            (cond (string? node) (update acc w str node)
                  (vector? node) (let [[t & more] node
                                       attrs (when (map? (first more)) (first more))
                                       kids  (if attrs (rest more) more)
                                       [tag classes] (tag-parts t)
                                       classes (into classes (some-> (:class attrs) (str/split #"\s+")))]
                                   (reduce #(walk %1 %2 (weight-of (set sans-langs) tag classes attrs w)) acc kids))
                  (seq? node) (reduce #(walk %1 %2 w) acc node)
                  :else acc))]
    (walk {:regular "" :bold "" :sans ""} body :regular)))

(defn needed
  "The characters a page from `pages` sets in Clogem Kai, by face:
  {:regular #{} :bold #{}}, empty on a page in the sans."
  [page]
  (let [text  (page-text page)
        chars (fn [s] (if (:kai? page) (into (sorted-set) (remove #(Character/isWhitespace (int %))) s) (sorted-set)))]
    {:regular (chars (:regular text)) :bold (chars (:bold text))}))

(defn read-fonts
  "The subsets of Clogem Kai of the page `page` (from `pages`, by `key`)
  from `dir` (bb fonts makes them), as base64: {:regular .. :bold ..}; nil
  for a page in the sans. Warns when the page sets a character its subset
  lacks (it then falls back to the sans), as after an edit to the words
  without bb fonts; bb test fails on it."
  [dir key page]
  (when (:kai? page)
    (let [file (fn [w] (fs/file dir (str key "-" w ".woff2")))
          b64  (fn [f] (.encodeToString (Base64/getEncoder) ^bytes (fs/read-all-bytes f)))
          made (some-> (fs/file dir "subsets.edn") (#(when (fs/exists? %) (edn/read-string (slurp %)))) :pages (get key))]
      (when-not (every? fs/exists? [(file "regular") (file "bold")])
        (fail! (str "Clogem Kai's subsets for " key " aren't in " dir ": run bb fonts (README, \"Typography\")")))
      (doseq [[w chars] (needed page)
              :let [lacking (remove (set (get made w "")) chars)]
              :when (seq lacking)]
        (warn! key "sets characters its" (name w) "subset lacks (run bb fonts):" (apply str lacking)))
      {:regular (b64 (file "regular")) :bold (b64 (file "bold"))})))

;; ---------------------------------------------------------------------------
;; The site in its languages

(defn- read-translations
  "Each language's translation but the first's, from `dir`: [id map]."
  [languages dir]
  (vec (for [{:keys [id]} (rest languages)
             :let [f (fs/file dir (str id ".edn"))]]
         (if (fs/exists? f)
           [id (edn/read-string (slurp f))]
           (fail! (str "The " id " translation isn't there: " f))))))

(defn load-site
  "site.edn from `config` and its translations from `i18n`, checked; the
  apps published in `site-dir` (all of them without one) and the projects
  with their icons from `root`. {:site :languages :sites}: `sites` is the
  site in each language's words, by :id."
  [{:keys [config i18n site-dir root]}]
  (let [site   (edn/read-string (slurp config))
        _      (when-let [problems (seq (check-site site))]
                 (fail! (str "site/site.edn isn't right:\n  " (str/join "\n  " problems))))
        languages (:languages site)
        i18n   (or i18n (str (fs/path (or (fs/parent config) ".") "i18n")))
        translations (read-translations languages i18n)
        _      (when-let [problems (seq (mapcat (fn [[id tr]] (translation-problems (words site) tr (str (fs/file i18n (str id ".edn")))))
                                                translations))]
                 (fail! (str "A translation isn't right:\n  " (str/join "\n  " problems))))
        _      (when (and site-dir (not (fs/directory? site-dir))) (fail! site-dir "isn't a folder"))
        projects (published-pages (project-icons (get-in site [:open-source :projects]) (or root ".")) site-dir languages)
        apps   (vec (published-apps (:apps site) site-dir languages))
        _      (when (empty? apps) (fail! "No app is published yet: nothing to list."))
        listed (-> site (assoc :apps apps) (assoc-in [:open-source :projects] projects))]
    {:site listed
     :languages languages
     :sites (into {(:id (first languages)) listed}
                  (for [[id tr] translations] [id (localize listed tr)]))}))

(defn -main [& args]
  (let [valued #{"--out" "--site-dir" "--config" "--root" "--i18n" "--fonts"}
        opts   (loop [[a & more :as as] args, out {}]
                 (cond (empty? as) out
                       (valued a)  (if (seq more) (recur (rest more) (assoc out a (first more)))
                                       (fail! a "needs a value"))
                       :else       (fail! "bb portal [--site-dir DIR] [--out DIR] [--i18n DIR] [--fonts DIR], not" a)))
        {:keys [site languages sites]}
        (load-site {:config   (get opts "--config" "site/site.edn")
                    :i18n     (get opts "--i18n")
                    :site-dir (get opts "--site-dir")
                    :root     (get opts "--root")})
        fonts  (get opts "--fonts" "fonts")
        out    (get opts "--out" "_site")
        year   (str (java.time.Year/now))]
    (fs/create-dirs out)
    (doseq [[key {:keys [file] :as page}] (pages sites year languages)
            :let [f (fs/file out file)]]
      (fs/create-dirs (fs/parent f))
      (spit f (page-html languages page (read-fonts fonts key page))))
    (spit (fs/file out ".nojekyll") "")
    (println (str "Wrote " out "/: index.html, " (str/join ", " (for [l (rest languages)] (str (:id l) "/index.html")))
                  ", 404.html and .nojekyll, listing "
                  (str/join ", " (concat (map :name (get-in site [:open-source :projects])) (map :name (:apps site))))
                  ", for " (:url site)))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
