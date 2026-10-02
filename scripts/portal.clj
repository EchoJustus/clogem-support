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

  Like the apps' pages: one self-contained file each, no JavaScript, nothing
  loaded from another site, and a Content-Security-Policy that allows only
  the page's own style (by its SHA-256) and data: images. An app's icon is
  taken from its published page (the data: URI of its favicon), so nothing
  is copied here from the apps' repositories."
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

(defn check-site
  "site.edn's problems, in words."
  [{:keys [url title lead apps issues business]}]
  (concat
   (for [[k v] {:url url :title title :lead lead :issues issues :business business}
         :when (or (not (string? v)) (str/blank? v))]
     (str k " must be text"))
   (when-not (and (string? url) (str/starts-with? url "https://") (str/ends-with? url "/"))
     [":url must be https and end with /"])
   (when-not (and (string? issues) (str/starts-with? issues "https://"))
     [":issues must be an https address"])
   (when-not (and (string? business) (re-matches #"[^\s@<>\"]+@[^\s@<>\"]+\.[A-Za-z]{2,}" business))
     [":business must be an email address"])
   (when-not (seq apps) [":apps must list at least one app"])
   (when (and (seq apps) (not (apply distinct? (map :folder apps))))
     [":apps must each have their own :folder"])
   (for [{:keys [folder name subtitle about accent] :as app} apps
         problem [(when-not (and (string? folder) (re-matches #"[a-z0-9][a-z0-9-]*" folder))
                    "its :folder must be one lowercase folder name")
                  (when (some #(or (not (string? %)) (str/blank? %)) [name subtitle about])
                    "it needs :name, :subtitle and :about")
                  (when-not (and (= 2 (count accent)) (every? #(and (string? %) (re-matches #"#[0-9A-Fa-f]{6}" %)) accent))
                    "its :accent must be two colours like #7A63DC")]
         :when problem]
     (str "app " (pr-str (or folder app)) ": " problem))))

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

(defn css
  "The page's styles, one accent rule per app (inline style attributes would
  need the CSP to allow them)."
  [apps]
  (str/join
   "\n"
   (concat
    [":root{color-scheme:light dark;"
     "--bg:#F4F6F9;--glow-1:rgba(79,128,227,.16);--glow-2:rgba(124,98,226,.12);"
     "--surface:#FFFFFF;--surface-2:#EEF1F6;--border:rgba(20,28,45,.11);"
     "--text:#141922;--muted:#525C70;--heading:#0D1220;--link:#2456B0;"
     "--chip:#E8EEFB;--chip-ink:#1D4C9E;--focus:#2F6FE4;"
     "--shadow:0 1px 2px rgba(13,18,32,.05),0 18px 40px -24px rgba(36,86,176,.32)}"
     "@media (prefers-color-scheme:dark){:root{"
     "--bg:#0D1016;--glow-1:rgba(79,128,227,.20);--glow-2:rgba(124,98,226,.18);"
     "--surface:#151A23;--surface-2:#1A202B;--border:rgba(160,175,205,.15);"
     "--text:#E9EDF4;--muted:#A2ABBC;--heading:#F5F7FB;--link:#9FBBFF;"
     "--chip:rgba(159,187,255,.12);--chip-ink:#B9CCFF;--focus:#9FBBFF;"
     "--shadow:0 1px 2px rgba(0,0,0,.35),0 24px 48px -24px rgba(0,0,0,.65)}}"
     (str "*,*::before{box-sizing:border-box}"
          "html{-webkit-text-size-adjust:100%;text-size-adjust:100%}")
     (str "body{margin:0;min-height:100vh;color:var(--text);background:"
          "radial-gradient(1100px 560px at 8% -12%,var(--glow-1),transparent 62%),"
          "radial-gradient(900px 520px at 100% -4%,var(--glow-2),transparent 58%),var(--bg);"
          "font:400 1rem/1.65 \"Segoe UI Variable Text\",\"Segoe UI\",system-ui,-apple-system,BlinkMacSystemFont,Roboto,\"Helvetica Neue\",Arial,sans-serif;"
          "-webkit-font-smoothing:antialiased}")
     ".page{max-width:58rem;margin:0 auto;padding:clamp(2.5rem,7vw,5.5rem) 1.25rem 2.5rem}"
     "a{color:var(--link);text-underline-offset:.18em}"
     "a:focus-visible{outline:3px solid var(--focus);outline-offset:3px;border-radius:.45rem}"
     "h1,h2,h3{font-family:\"Segoe UI Variable Display\",\"Segoe UI\",system-ui,-apple-system,BlinkMacSystemFont,Roboto,sans-serif;color:var(--heading)}"
     ".hero{display:grid;gap:1.4rem}"
     ".brand{display:flex;align-items:center;gap:clamp(.9rem,2.5vw,1.25rem)}"
     ".mark{flex:none;width:clamp(3.25rem,8vw,4.25rem);height:clamp(3.25rem,8vw,4.25rem)}"
     ".mark svg{display:block;width:100%;height:100%}"
     ".eyebrow{margin:0 0 .2rem;font-size:.78rem;font-weight:700;letter-spacing:.16em;text-transform:uppercase;color:var(--chip-ink)}"
     "h1{margin:0;font-size:clamp(2.2rem,5.2vw,3.3rem);line-height:1.05;letter-spacing:-.025em;font-weight:700}"
     (str ".rule{width:min(11rem,40%);height:3px;border-radius:3px;background:linear-gradient(90deg,"
          (str/join "," family) ")}")
     ".lead{margin:0;max-width:38rem;font-size:1.15rem;color:var(--muted)}"
     "main{display:grid;gap:clamp(3rem,7vw,4.5rem);margin-top:clamp(3rem,7vw,4.5rem)}"
     "h2{margin:0;font-size:clamp(1.45rem,3.2vw,1.8rem);letter-spacing:-.015em}"
     (str "h2::after{content:\"\";display:block;width:2.6rem;height:3px;margin-top:.65rem;border-radius:3px;"
          "background:linear-gradient(90deg," (str/join "," family) ")}")
     ".section-lead{margin:.9rem 0 1.6rem;color:var(--muted)}"
     ".apps{display:grid;gap:1.25rem;grid-template-columns:repeat(auto-fit,minmax(min(100%,24rem),1fr))}"
     (str ".app{position:relative;overflow:hidden;display:grid;grid-template-columns:auto 1fr;gap:.35rem 1.1rem;align-items:start;"
          "padding:1.75rem 1.6rem 1.5rem;background:var(--surface);border:1px solid var(--border);border-radius:1.25rem;box-shadow:var(--shadow)}")
     ".app::before{content:\"\";position:absolute;inset:0 0 auto 0;height:4px}"
     ".app-icon{grid-row:span 2;width:4rem;height:4rem;display:block}"
     ".app-icon.plain{border-radius:1rem;background:var(--surface-2);border:1px solid var(--border)}"
     ".app h3{margin:.15rem 0 0;font-size:1.25rem;line-height:1.25}"
     ".app .sub{margin:0;color:var(--muted);font-size:.98rem}"
     ".app .about{grid-column:1 / -1;margin:.6rem 0 0}"
     ".links{grid-column:1 / -1;display:flex;flex-wrap:wrap;gap:.55rem;margin-top:.9rem}"
     (str ".links a{display:inline-block;padding:.45rem .95rem;border-radius:999px;border:1px solid var(--border);"
          "background:var(--surface-2);color:var(--text);font-weight:600;font-size:.92rem;text-decoration:none}")
     ".links a:hover{border-color:var(--focus)}"
     ".links a.primary{background:var(--chip);color:var(--chip-ink);border-color:transparent}"
     ".help{display:grid;gap:1rem}"
     ".help-item{padding:1.25rem 1.5rem;background:var(--surface);border:1px solid var(--border);border-radius:1.1rem}"
     ".help-item h3{margin:0 0 .3rem;font-size:1.05rem}"
     ".help-item p{margin:0;color:var(--muted)}"
     ".help-item p+p{margin-top:.45rem}"
     ".help-item .go{font-weight:650;overflow-wrap:anywhere}"
     ".notfound p{margin:.9rem 0 1.4rem;color:var(--muted);font-size:1.1rem}"
     (str "footer{display:flex;flex-wrap:wrap;justify-content:space-between;gap:.4rem 1.5rem;margin-top:clamp(3.5rem,9vw,5.5rem);"
          "padding-top:1.4rem;border-top:1px solid var(--border);font-size:.88rem;color:var(--muted)}")
     "footer p{margin:0}"
     (str "@media print{:root{--bg:#fff;--surface:#fff;--surface-2:#fff;--text:#000;--muted:#333;--heading:#000;--link:#000;--border:#bbb;--shadow:none}"
          "body{background:#fff}.app::before{display:none}}")]
    (for [{:keys [folder accent]} apps]
      (format ".app-%s::before{background:linear-gradient(90deg,%s,%s)}" folder (first accent) (second accent))))))

(defn csp
  "Nothing but the page's own style (by the SHA-256 of `style`) and data:
  images (the favicon and the apps' icons)."
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

(defn- header [site]
  [:header.hero
   [:div.brand
    [:span.mark {:aria-hidden "true"} (h/raw (hub-mark))]
    [:div
     [:p.eyebrow "Clogem"]
     [:h1 "Support"]]]
   [:div.rule]
   [:p.lead (:lead site)]])

(defn- footer [year]
  [:footer
   [:p (str "© " year " EchoJustus.")]
   [:p "This page sets no cookies and loads nothing from other sites."]])

(defn- mailto [email subject]
  (str "mailto:" email "?subject=" (str/replace (URLEncoder/encode ^String subject "UTF-8") "+" "%20")))

(defn index-page
  "The front page: the apps, then where to turn."
  [site apps year]
  (let [style (css apps)]
    (str
     "<!doctype html>\n"
     (h/html
      {:mode :html}
      [:html {:lang "en"}
       (head site style (:title site) (str (:lead site) " Each app's support page has its contacts and its privacy policy."))
       [:body
        [:div.page
         (header site)
         [:main
          [:section#apps {:aria-labelledby "apps-title"}
           [:h2#apps-title "Apps"]
           [:p.section-lead "Each app has its own page: how to reach us about it, what to include, and its privacy policy."]
           [:div.apps
            (for [{:keys [folder name subtitle about icon]} apps]
              [:article {:class (str "app app-" folder)}
               (if icon
                 [:img.app-icon {:src icon :alt "" :width "64" :height "64"}]
                 [:span.app-icon.plain {:aria-hidden "true"}])
               [:h3 name]
               [:p.sub subtitle]
               [:p.about about]
               [:nav.links {:aria-label (str name " links")}
                [:a.primary {:href (str folder "/")} "Support & contact"]
                [:a {:href (str folder "/#privacy")} "Privacy policy"]]])]]
          [:section#help {:aria-labelledby "help-title"}
           [:h2#help-title "Get help"]
           [:p.section-lead "Start with the app's page. Then:"]
           [:div.help
            [:div.help-item
             [:h3 "Report a problem or suggest a feature"]
             [:p "Open an issue on GitHub, and say which app and version."]
             [:p "Issues are public: leave out personal details, license keys and videos."]
             [:p [:a.go {:href (:issues site)} "GitHub Issues"]]]
            [:div.help-item
             [:h3 "Business, privacy and legal"]
             [:p "Partnerships, licensing, questions about a privacy policy or your data, and legal notices."]
             [:p [:a.go {:href (mailto (:business site) "Clogem")} (:business site)]]]]]]
         (footer year)]]]))))

(defn not-found-page
  "The 404 page GitHub Pages shows for any address in the hub that isn't
  there. Its links are absolute: it is served at the missing address."
  [site apps year]
  (let [style (css apps)]
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
  (let [valued #{"--out" "--site-dir" "--config"}
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
        apps   (vec (published-apps (:apps site) dir))
        _      (when (empty? apps) (fail! "No app is published yet: nothing to list."))
        out    (get opts "--out" "_site")
        year   (str (java.time.Year/now))]
    (fs/create-dirs out)
    (spit (fs/file out "index.html") (index-page site apps year))
    (spit (fs/file out "404.html") (not-found-page site apps year))
    (spit (fs/file out ".nojekyll") "")
    (println (str "Wrote " out "/index.html, 404.html and .nojekyll, listing "
                  (str/join ", " (map :name apps)) ", for " (:url site)))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
