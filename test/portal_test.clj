;; Copyright 2026 EchoJustus. Part of clogem-support.
;; SPDX-License-Identifier: MIT
(ns portal-test
  "bb portal (scripts/portal.clj): the hub's front page in its five
  languages and its 404 page, built from site/site.edn, site/i18n/ and the
  apps' folders on gh-pages, English and Chinese set in Clogem Kai from
  fonts/ (bb fonts, scripts/fonts.clj); and .github/workflows/portal.yml,
  whose publishing step is run here against a local repository standing in
  for gh-pages."
  (:require [babashka.fs :as fs]
            [fonts]
            [portal]
            [babashka.process :as p]
            [clj-yaml.core :as yaml]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.util Base64)))

(def ^:private site (edn/read-string (slurp "site/site.edn")))

(def ^:private icon
  "An app's favicon, as its page carries it"
  (str "data:image/svg+xml;base64,"
       (.encodeToString (Base64/getEncoder)
                        (.getBytes "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 8 8\"/>" StandardCharsets/UTF_8))))

(defn- temp-dir [] (str (fs/create-temp-dir {:prefix "portal-test"})))

(defn- app-page!
  "An app's published page in `site-dir`, with `head` in its <head>."
  [site-dir folder head]
  (let [f (fs/file site-dir folder "index.html")]
    (fs/create-dirs (fs/parent f))
    (spit f (str "<!doctype html>\n<html><head>" head "</head><body><h1>" folder "</h1></body></html>\n"))))

(def ^:private languages (:languages site))

(def ^:private translations
  (into {} (for [{:keys [id]} (rest languages)]
             [id (edn/read-string (slurp (str "site/i18n/" id ".edn")))])))

(defn- config!
  "site.edn with `f` applied, written to a folder of its own beside the
  translations, each with `g` applied (they are read from there)."
  [f & [g]]
  (let [dir  (temp-dir)
        file (fs/file dir "site.edn")]
    (spit file (pr-str (f site)))
    (fs/create-dirs (fs/file dir "i18n"))
    (doseq [[id tr] translations]
      (spit (fs/file dir "i18n" (str id ".edn")) (pr-str ((or g identity) tr))))
    (str file)))

(defn- portal!
  "Runs `bb portal`; its exit, its words and what it wrote."
  [site-dir & args]
  (let [out (temp-dir)
        r   (apply p/shell {:out :string :err :string :continue true}
                   "bb" "scripts/portal.clj" "--site-dir" (str site-dir) "--out" out args)
        read (fn [n] (let [f (fs/file out n)] (when (fs/exists? f) (slurp f))))]
    {:exit (:exit r) :said (str (:out r) (:err r)) :out out
     :index (read "index.html") :not-found (read "404.html") :nojekyll (read ".nojekyll")
     ;; each language's front page, by :id
     :pages (into {} (for [{:keys [id]} languages]
                       [id (read (if (= id "en") "index.html" (str id "/index.html")))]))}))

(defn- sha256-base64 [^String s]
  (.encodeToString (Base64/getEncoder)
                   (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s StandardCharsets/UTF_8))))

(def ^:private wmark-pro-head
  (str "<link href=\"" icon "\" rel=\"icon\" type=\"image/svg+xml\">"))

(deftest the-front-page-lists-the-published-apps
  (let [pages (temp-dir)
        _     (app-page! pages "wmark-pro" wmark-pro-head)
        {:keys [exit said index not-found nojekyll]} (portal! pages)]
    (is (zero? exit) said)
    (testing "each app's card links to its page and its policy, with its own icon"
      (is (str/includes? index "Wmark Pro"))
      (is (str/includes? index "Video Watermarker"))
      (is (str/includes? index "href=\"wmark-pro/\""))
      (is (str/includes? index "href=\"wmark-pro/#privacy\""))
      (is (re-find (re-pattern (str "<img [^>]*src=\"" (java.util.regex.Pattern/quote icon) "\"")) index)))
    (testing "where to get it: the store's button, with the app's name for screen readers"
      (let [{:keys [url note] store :name} (get-in site [:apps 0 :store])]
        (is (re-find (re-pattern (str "<a [^>]*class=\"store\"[^>]*href=\"" (java.util.regex.Pattern/quote url) "\""
                                      "|<a [^>]*href=\"" (java.util.regex.Pattern/quote url) "\"[^>]*class=\"store\""))
                     index))
        (is (str/includes? index (str "aria-label=\"Get Wmark Pro from the " store "\"")))
        (is (str/includes? index note))
        (is (= "https://apps.microsoft.com/detail/9PMP1591Q78R" url) "Wmark Pro's live listing (2026-10-03)")
        (is (not (re-find #"(?i)microsoft[^\"<]*logo|<img [^>]*store" index)) "our own button, no store's logo")))
    (testing "the open-source projects, in site.edn's order, each with its icon and its source or its status"
      (let [projects (get-in site [:open-source :projects])
            at       (map #(str/index-of index (str "class=\"project project-" (:id %) "\"")) projects)]
        (is (every? some? at))
        (is (apply < at) "in site.edn's order")
        (is (str/includes? index (get-in site [:open-source :title])))
        (doseq [{:keys [repo status license]} projects]
          (if repo
            (do (is (str/includes? index (str "href=\"" repo "\"")))
                (is (str/includes? index (str "Open source · " license))))
            (is (str/includes? index (str/replace status "'" "&apos;")))))
        (is (= (count projects) (count (re-seq #"<img [^>]*class=\"app-icon\" [^>]*src=\"data:image/(?:svg\+xml|png);base64,"
                                               (subs index (str/index-of index "id=\"open-source\"") (str/index-of index "id=\"feedback\"")))))
            "every project's icon, embedded")))
    (testing "the commercial apps first (owner, 2026-10-05), with the note about Pro editions to come, then open source"
      (is (< (str/index-of index "id=\"apps\"") (str/index-of index "id=\"open-source\"") (str/index-of index "id=\"feedback\"")))
      (is (< (str/index-of index "href=\"#apps\"") (str/index-of index "href=\"#open-source\"")) "and so do the jump links")
      (is (str/includes? index (get-in site [:commercial :title])))
      (is (str/includes? index (get-in site [:commercial :note]))))
    (testing "feedback in the open: each channel opens its discussion category or issue form"
      (let [{:keys [repo channels note]} (:feedback site)]
        (doseq [{:keys [discussion issue-form action]} channels]
          (is (str/includes? index (if discussion
                                     (str "href=\"" repo "/discussions/new?category=" discussion "\"")
                                     (str "href=\"" repo "/issues/new?template=" issue-form "\""))))
          (is (str/includes? index action)))
        (is (str/includes? index (str "href=\"" repo "/discussions\"")))
        (is (str/includes? index (str/replace note "'" "&apos;")))))
    (testing "and the business address, for what shouldn't be public"
      (is (str/includes? index (str "href=\"mailto:" (:business site) "?subject=Clogem\""))))
    (testing "the 404 page: served at any missing address, so its links are absolute"
      (is (str/includes? not-found "Page not found"))
      (is (str/includes? not-found (str "href=\"" (:url site) "\"")))
      (is (str/includes? not-found (str "href=\"" (:url site) "wmark-pro/\"")))
      (is (not (re-find #"href=\"(?!https://|data:)" not-found))))
    (testing ".nojekyll: Pages serves the files as they are"
      (is (= "" nojekyll)))))

(deftest the-hub-wears-its-own-mark
  ;; the Clogem Support mark (owner, 2026-10-27), from icons/ (icons/README.md)
  (let [drawing (fn [f] (str/trim (str/replace (slurp f) #"(?s)<!--.*?-->" "")))
        header  (drawing "icons/clogem-support.svg")
        favicon (drawing "icons/clogem-support-favicon.svg")
        uri     (str "data:image/svg+xml;base64,"
                     (.encodeToString (Base64/getEncoder) (.getBytes ^String favicon StandardCharsets/UTF_8)))]
    (testing "both drawings are shapes alone, fit to draw inline: nothing that runs, styles or reaches outside them"
      (is (nil? (portal/inline-svg-problem header)))
      (is (nil? (portal/inline-svg-problem favicon))))
    (testing "what an inline mark may not hold, though a data: icon could"
      (doseq [[what svg] {"a style element"   "<svg><style>path{fill:red}</style></svg>"
                          "a style attribute" "<svg><path style=\"fill:red\"/></svg>"
                          "a link"            "<svg><a href=\"#x\"><path/></a></svg>"
                          "an animation"      "<svg><path><set attributeName=\"d\" to=\"M0 0\"/></path></svg>"
                          "a meta element"    "<svg><meta http-equiv=\"refresh\" content=\"0\"/></svg>"
                          "a script"          "<svg><script>alert(1)</script></svg>"}]
        (is (some? (portal/inline-svg-problem svg)) what))
      (is (nil? (portal/inline-svg-problem "<svg><defs><linearGradient id=\"g\"><stop/></linearGradient><clipPath id=\"c\"><path/></clipPath></defs><g clip-path=\"url(#c)\"><path fill=\"url(#g)\"/><ellipse/></g></svg>"))))
    (testing "every page shows the mark in its header and as its favicon, in place of the old four tiles"
      (let [pages (temp-dir)
            _     (app-page! pages "wmark-pro" wmark-pro-head)
            {:keys [exit said pages not-found]} (portal! pages)]
        (is (zero? exit) said)
        (doseq [[n html] (assoc pages "404.html" not-found)]
          (testing n
            (is (str/includes? html (str "<span aria-hidden=\"true\" class=\"mark\">" header "</span>")))
            (is (str/includes? html (str "<link href=\"" uri "\" rel=\"icon\" type=\"image/svg+xml\">")))
            (is (not (str/includes? html "rx=\"6\" fill=\"#4F80E3\"")) "the old tiles are gone")
            (is (not (str/includes? html "<!--")) "the licence comment stays in the file")
            (let [ids (map second (re-seq #"\sid=\"([^\"]+)\"" html))]
              (is (= (count ids) (count (set ids))) "the mark's ids clash with nothing on the page"))))))
    (testing "an unfit mark stops the build, checked as the page will carry it"
      (doseq [[what bad reason] [["a script" "<script>alert(1)</script></svg>" "it has a script"]
                               ["a script split by a comment" "<scr<!---->ipt>alert(1)</scr<!---->ipt></svg>" "it has a script"]
                               ["a style element" "<style>path{fill:red}</style></svg>" "it has a style element"]]]
        (testing what
          (let [root  (temp-dir)
                pages (temp-dir)
                _     (app-page! pages "wmark-pro" wmark-pro-head)
                f     (fs/file root "icons" "clogem-support.svg")]
            (fs/copy-tree "icons" (fs/path root "icons"))
            (spit f (str/replace (slurp f) "</svg>" bad))
            (let [{:keys [exit said]} (portal! pages "--root" root)]
              (is (= 1 exit))
              (is (str/includes? said (str "the hub's mark icons/clogem-support.svg: " reason)) said))))))
    (testing "a missing mark stops the build"
      (let [root  (temp-dir)
            pages (temp-dir)
            _     (app-page! pages "wmark-pro" wmark-pro-head)]
        (fs/copy-tree "icons" (fs/path root "icons"))
        (fs/delete (fs/path root "icons" "clogem-support-favicon.svg"))
        (let [{:keys [exit said]} (portal! pages "--root" root)]
          (is (= 1 exit))
          (is (str/includes? said "the hub's mark icons/clogem-support-favicon.svg isn't there") said))))))

(deftest the-pages-run-nothing-and-load-nothing-from-elsewhere
  (let [pages (temp-dir)
        _     (app-page! pages "wmark-pro" wmark-pro-head)
        {:keys [pages not-found]} (portal! pages)]
    (doseq [[n html] (assoc pages "404.html" not-found)]
      (testing n
        (is (not (str/includes? (str/lower-case html) "<script")))
        (is (not (re-find #"(?i)\son[a-z]+=" html)) "no event handlers")
        (is (not (re-find #"\sstyle=" html)) "no style attributes: the CSP would block them")
        (is (every? #(str/starts-with? % "data:") (map second (re-seq #"\ssrc=\"([^\"]*)\"" html)))
            "images are data: URIs")
        (is (not (re-find #"<link [^>]*rel=\"stylesheet\"" html)))
        (is (= 1 (count (re-seq #"<style\b" html))) "one style element, the one the CSP allows by its hash")
        (let [style (second (re-find #"(?s)<style>(.*?)</style>" html))]
          (is (some? style))
          (is (str/includes? html "http-equiv=\"Content-Security-Policy\""))
          (is (str/includes? (str/replace html "&apos;" "'")
                             (str "default-src 'none'; style-src 'sha256-" (sha256-base64 style) "'; img-src data:")))
          (testing "light and dark"
            (is (str/includes? style "@media (prefers-color-scheme:dark)"))
            (is (str/includes? html "name=\"color-scheme\""))))
        (is (str/includes? html "content=\"no-referrer\""))))))

(deftest each-section-is-introduced-once
  ;; the owner, 2026-10-02: no small overline repeating the heading under it
  (let [pages (temp-dir)
        _     (app-page! pages "wmark-pro" wmark-pro-head)
        {:keys [index]} (portal! pages)]
    (is (not (str/includes? index "kicker")))
    (doseq [[id title] [["open-source" (get-in site [:open-source :title])]
                        ["apps" (get-in site [:commercial :title])]
                        ["feedback" (get-in site [:feedback :title])]]]
      (let [section (second (re-find (re-pattern (str "(?s)<section [^>]*id=\"" id "\"[^>]*>(.*?)<h2")) index))]
        (is (= "" section) (str id ": the heading comes first in its section"))
        (is (str/includes? index (str/replace title "&" "&amp;")))))))

(deftest the-theme-switcher-takes-no-script
  ;; Auto, Light and Dark as radio buttons the stylesheet reads with :has(),
  ;; on every page; Auto (the system's setting) is chosen when a page opens
  (let [pages (temp-dir)
        _     (app-page! pages "wmark-pro" wmark-pro-head)
        {:keys [pages not-found]} (portal! pages)]
    (doseq [[n html] (assoc pages "404.html" not-found)]
      (testing n
        (let [radios (re-seq #"<input [^>]*name=\"theme\"[^>]*>" html)
              style  (second (re-find #"(?s)<style>(.*?)</style>" html))]
          (is (= ["theme-system" "theme-light" "theme-dark"] (map #(second (re-find #"id=\"([^\"]+)\"" %)) radios)))
          (is (every? #(str/includes? % "type=\"radio\"") radios))
          (is (= ["theme-system"] (keep #(when (str/includes? % " checked") (second (re-find #"id=\"([^\"]+)\"" %))) radios))
              "Auto when the page opens")
          (doseq [id ["system" "light" "dark"]]
            (is (str/includes? html (str "<label for=\"theme-" id "\""))))
          (is (re-find #"<fieldset class=\"theme\"><legend>[^<]+</legend>" html) "named for screen readers")
          (when (#{"en" "404.html"} n)
            (is (str/includes? html "<legend>Colour theme</legend>")))
          (testing "the stylesheet follows the system unless Light or Dark is chosen"
            (is (str/includes? style "@media (prefers-color-scheme:dark){:root:not(:has(#theme-light:checked)){color-scheme:dark;--bg:#0D1016"))
            (is (str/includes? style ":root:has(#theme-dark:checked){color-scheme:dark;--bg:#0D1016"))
            (is (str/includes? style ":root:has(#theme-light:checked){color-scheme:light}")))
          (testing "colours fade, unless the visitor asked for less motion"
            (is (str/includes? style "@property --bg{syntax:\"<color>\""))
            (is (re-find #":root\{color-scheme:light dark;[^}]*transition:--bg \.35s ease" style))
            (is (re-find #"prefers-reduced-motion:reduce\)\{:root," style)))
          (is (str/includes? style "@supports not selector(:has(*)){.theme{display:none}}")
              "hidden where the browser can't apply it")
          (is (not (str/includes? (str/lower-case html) "<script"))))))))

;; ---------------------------------------------------------------------------
;; The languages (owner, 2026-10-25)

(defn- esc
  "Text as hiccup writes it into a page."
  [s]
  (-> s (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;") (str/replace "'" "&apos;")))

(defn- page-url [id] (str (:url site) (when-not (= id "en") (str id "/"))))

(deftest every-language-has-its-page
  ;; English at the root, the others in their folders, each telling search
  ;; engines where the rest are
  (let [pages (temp-dir)
        _     (app-page! pages "wmark-pro" wmark-pro-head)
        {:keys [exit said pages]} (portal! pages)]
    (is (zero? exit) said)
    (is (= ["en" "zh-hant" "zh-hans" "pt" "es"] (map :id languages))
        "the owner's five, English first, Traditional Chinese before Simplified (2026-10-26)")
    (is (= {"en" "en" "zh-hans" "zh-Hans" "zh-hant" "zh-Hant-TW" "pt" "pt-BR" "es" "es-ES"}
           (into {} (map (juxt :id :lang) languages)))
        "Simplified Chinese; Traditional in Taiwan's words; Brazil's Portuguese; Spain's Spanish")
    (doseq [{:keys [id lang]} languages
            :let [html (pages id)
                  tr   (if (= id "en") site (translations id))]]
      (testing id
        (is (some? html))
        (is (str/includes? html (str "<html lang=\"" lang "\">")))
        (is (str/includes? html (str "<link href=\"" (page-url id) "\" rel=\"canonical\">")))
        (testing "every language's address, and English's for anyone else"
          (doseq [l languages]
            (is (str/includes? html (str "<link href=\"" (page-url (:id l)) "\" hreflang=\"" (:hreflang l) "\" rel=\"alternate\">"))))
          (is (str/includes? html (str "<link href=\"" (:url site) "\" hreflang=\"x-default\" rel=\"alternate\">"))))
        (testing "in its own words"
          (is (str/includes? html (str "<title>" (esc (:title tr)) "</title>")))
          (is (str/includes? html (str "<h1>" (esc (get-in tr [:ui :heading])) "</h1>")))
          (doseq [path [[:lead] [:open-source :title] [:commercial :note] [:feedback :lead]
                        [:apps "wmark-pro" :about] [:open-source :projects "clogem-press" :about]
                        [:feedback :channels "bug-report.yml" :action]]]
            (is (str/includes? html (esc (get-in (if (= id "en") (portal/words site) tr) path))) (pr-str path))))
        (testing "the names stay as they are"
          (doseq [n ["Wmark Pro" "Video Watermarker" "Microsoft Store" "clogem-press" "Clogem"]]
            (is (str/includes? html n) n)))))))

(deftest the-languages-are-a-dropdown
  ;; owner, 2026-10-25: five names in a row are too many; a <details> opens
  ;; without a script
  (let [pages (temp-dir)
        _     (app-page! pages "wmark-pro" wmark-pro-head)
        {:keys [pages not-found]} (portal! pages)]
    (doseq [[id html] (assoc pages "404.html" not-found)
            :let [current (if (= id "404.html") "en" id)
                  {:keys [lang name]} (some #(when (= current (:id %)) %) languages)
                  menu (re-find #"(?s)<details class=\"langs\">.*?</details>" html)
                  links (re-seq #"<a [^>]*>" (or menu ""))]]
      (testing id
        (is (some? menu) "one dropdown")
        (is (= 1 (count (re-seq #"<details" html))))
        (is (re-find (re-pattern (str "<summary>.*<span class=\"sr\">[^<]+</span><span lang=\"" lang "\">"
                                      (java.util.regex.Pattern/quote name) "</span>"))
                     menu)
            "it shows the current language, after the word for language")
        (is (= (map :lang languages) (map #(second (re-find #"\slang=\"([^\"]+)\"" %)) links))
            "every language, each named in its own")
        (is (= (map :hreflang languages) (map #(second (re-find #"hreflang=\"([^\"]+)\"" %)) links)))
        (is (= [lang] (keep #(when (str/includes? % "aria-current=\"page\"") (second (re-find #"\slang=\"([^\"]+)\"" %))) links))
            "the current one marked")
        (when (= id "404.html")
          (is (every? #(re-find #"href=\"https://" %) links) "absolute from the 404 page"))))
    (testing "the stylesheet: no marker, a panel below the button, hidden in print"
      (let [style (second (re-find #"(?s)<style>(.*?)</style>" (pages "en")))]
        (is (str/includes? style ".langs summary::-webkit-details-marker{display:none}"))
        (is (str/includes? style ".langs .menu{position:absolute;right:0;top:calc(100% + .45rem)"))
        (is (str/includes? style ".jump,.theme,.langs{display:none}}"))))))

(defn- resolves?
  "Whether a relative link `href` from the page at `page` (its path in the
  hub) reaches something: a page we wrote in `out`, or an app's in `pages`."
  [out pages page href]
  (let [path (first (str/split href #"#"))
        dir  (or (fs/parent page) (fs/path ""))
        target (str (fs/normalize (fs/path dir path)))
        target (if (or (= "" path) (str/ends-with? path "/")) (str (fs/path target "index.html")) target)]
    (or (fs/exists? (fs/file out target)) (fs/exists? (fs/file pages target)))))

(deftest every-link-leads-somewhere
  (let [pages (temp-dir)
        _     (app-page! pages "wmark-pro" wmark-pro-head)
        {:keys [out]} (portal! pages)]
    (doseq [{:keys [id]} languages
            :let [page (if (= id "en") "index.html" (str id "/index.html"))
                  html (slurp (fs/file out page))
                  ids  (set (map second (re-seq #"\sid=\"([^\"]+)\"" html)))]
            href (map second (re-seq #"\shref=\"([^\"]+)\"" html))
            :when (not (re-find #"^(?:https://|mailto:|data:)" href))]
      (testing (str page " → " href)
        (if (str/starts-with? href "#")
          (is (contains? ids (subs href 1)) "a section of the page")
          (is (resolves? out pages page href)))))
    (testing "an app's page is in English: from another language, its links say so"
      (let [pt (slurp (fs/file out "pt" "index.html"))]
        (is (str/includes? pt (str "<a class=\"primary\" href=\"../wmark-pro/\" hreflang=\"en\">"
                                   (esc (get-in translations ["pt" :ui :features])) (esc (get-in translations ["pt" :ui :in-english])) "</a>")))
        (is (str/includes? (slurp (fs/file out "index.html")) "<a class=\"primary\" href=\"wmark-pro/\">Features &amp; support</a>"))))))

(deftest an-app-page-in-the-readers-language-is-linked-as-it-is
  ;; Wmark Pro's page in five languages (2026-10-26): each language's card
  ;; leads to the app's page in it, once that folder is on gh-pages
  (let [pages (temp-dir)
        _     (app-page! pages "wmark-pro" wmark-pro-head)
        _     (app-page! pages "wmark-pro/zh-hant" "")
        {:keys [exit said out]} (portal! pages)
        page  (fn [id] (slurp (fs/file out id "index.html")))
        ui    (fn [id] (get-in translations [id :ui]))]
    (is (zero? exit) said)
    (testing "Traditional Chinese: the app's own Traditional Chinese page, not marked as English"
      (is (str/includes? (page "zh-hant") (str "<a class=\"primary\" href=\"../wmark-pro/zh-hant/\">" (esc (:features (ui "zh-hant"))) "</a>")))
      (is (str/includes? (page "zh-hant") (str "<a href=\"../wmark-pro/zh-hant/#privacy\">" (esc (:privacy (ui "zh-hant"))) "</a>")))
      (is (not (str/includes? (page "zh-hant") (:in-english (ui "zh-hant"))))))
    (testing "a language the app's page isn't in yet: its English page, marked"
      (is (str/includes? (page "pt") (str "<a class=\"primary\" href=\"../wmark-pro/\" hreflang=\"en\">"
                                          (esc (:features (ui "pt"))) (esc (:in-english (ui "pt"))) "</a>"))))
    (testing "English: the root page, as before"
      (is (str/includes? (slurp (fs/file out "index.html")) "<a class=\"primary\" href=\"wmark-pro/\">Features &amp; support</a>")))))

(deftest every-translation-gives-every-word
  (let [english (portal/words site)]
    (doseq [[id tr] translations
            :let [file (str "site/i18n/" id ".edn")]]
      (testing id
        (is (= [] (vec (portal/translation-problems english tr file))))
        (is (str/starts-with? (slurp file) ";; Copyright 2026 EchoJustus. All rights reserved."))
        (testing "translated: a word may match English only when it is short (Bug, Ideas, Apps)"
          (doseq [path (let [leaves (fn leaves [p m] (if (map? m) (mapcat (fn [[k v]] (leaves (conj p k) v)) m) [p]))]
                         (leaves [] english))
                  :let [e (get-in english path) v (get-in tr path)]
                  :when (= e v)]
            (is (< (count e) 10) (str (pr-str path) " is still English: " e))))
        (testing "the varieties the owner chose"
          (let [text (pr-str tr)]
            (case id
              "zh-hant" (do (is (re-find #"軟體" text)) (is (re-find #"影片" text)) (is (re-find #"浮水印" text))
                            (is (re-find #"專案" text))
                            (is (not (re-find #"軟件|視頻|項目|帳戶" text)) "not Hong Kong's or the mainland's words"))
              "zh-hans" (do (is (re-find #"软件" text)) (is (re-find #"视频" text))
                            (is (not (re-find #"[軟體視頻專案]" text)) "no Traditional characters"))
              "pt"      (do (is (re-find #"(?U)\bvocê\b" text)) (is (re-find #"\bcontato\b" text))
                            (is (not (re-find #"ficheiro|contacto|utilizador|aplicação" text)) "Brazil's Portuguese, not Portugal's"))
              "es"      (do (is (re-find #"vídeos?" text)) (is (re-find #"\bNecesitas\b" text) "tú, as in Spain's software")
                            (is (not (re-find #"\bvideos?\b|\bustedes\b|\breportar\b" text)) "Spain's Spanish"))
              nil))))))
  (testing "a translation that leaves a word out, adds one, or loses a placeholder stops the build"
    (let [pages (temp-dir)
          _     (app-page! pages "wmark-pro" wmark-pro-head)]
      (doseq [[said g] {"missing [:feedback :note]"             #(update % :feedback dissoc :note)
                        "not in site.edn [:apps \"other\" :about]" #(assoc-in % [:apps "other"] {:about "x"})
                        "must keep the placeholders {app} {store}" #(assoc-in % [:ui :get-label] "Get it")
                        "must be text"                            #(assoc-in % [:commercial :title] " ")}]
        (let [{:keys [exit] out :said} (portal! pages "--config" (config! identity g))]
          (is (= 1 exit))
          (is (str/includes? out said) out))))))

(deftest chinese-is-set-in-chinese-fonts
  (let [pages (temp-dir)
        _     (app-page! pages "wmark-pro" wmark-pro-head)
        {:keys [pages]} (portal! pages)
        style (second (re-find #"(?s)<style>(.*?)</style>" (pages "zh-hant")))]
    (is (re-find #":lang\(zh-Hans\)\{--cjk:\"Microsoft YaHei UI\"[^}]*\"PingFang SC\"" style))
    (is (re-find #":lang\(zh-Hant\)\{--cjk:\"Microsoft JhengHei UI\"[^}]*\"PingFang TC\"" style)
        "Traditional characters in Traditional fonts, never a Japanese or Simplified fallback")
    (is (str/includes? style "Arial,var(--cjk);") "after the Latin face, so Latin text keeps it")
    (is (str/includes? style "[lang]:not(html){font-family:") "and a name in another language, in the menu, takes its own")
    (is (str/includes? style ":lang(zh) :is(h1,h2){letter-spacing:0}"))))

(deftest every-footer-names-the-publisher-in-full
  ;; CLAUDE.md, "Footer copyright notices" (owner, 2026-10-26): the hub's
  ;; pages name the publisher alone, never shortened or split
  (let [pages (temp-dir)
        _     (app-page! pages "wmark-pro" wmark-pro-head)
        {:keys [pages not-found]} (portal! pages)
        year  (str (java.time.Year/now))]
    (is (= "EchoJustus (Jiapeng Ji)" portal/copyright-holder))
    (doseq [[n html] (assoc pages "404" not-found)]
      (is (= [(str "© " year " EchoJustus (Jiapeng Ji).")]
             (map second (re-seq #"<footer><p>([^<]*)</p>" html)))
          n))
    (testing "the policy is written down for the next maintainer, whatever the agent"
      (let [policy (slurp "CLAUDE.md")]
        (is (str/includes? policy "## Footer copyright notices"))
        (is (str/includes? policy "© 2026 EchoJustus (Jiapeng Ji). Wmark Pro: Video Watermarker."))
        (is (str/includes? policy "© 2026 EchoJustus (Jiapeng Ji).\n")))
      (is (str/includes? (slurp "AGENTS.md") "Read `CLAUDE.md`")))))

(deftest the-404-page-speaks-every-language
  (let [pages (temp-dir)
        _     (app-page! pages "wmark-pro" wmark-pro-head)
        {:keys [not-found]} (portal! pages)]
    (doseq [{:keys [id lang hreflang]} (rest languages)
            :let [tr (translations id)]]
      (is (str/includes? not-found (str "<p lang=\"" lang "\">" (esc (get-in tr [:ui :not-found-text]))))
          id)
      (is (str/includes? not-found (str "<a href=\"" (page-url id) "\" hreflang=\"" hreflang "\">" (esc (:title tr)) "</a>"))
          id))
    (is (str/includes? not-found "此地址没有页面。<a ") "no space after a Chinese full stop")
    (is (str/includes? not-found "Não há nenhuma página neste endereço. <a "))))

(deftest an-app-is-listed-once-it-is-published
  (let [pages (temp-dir)
        _     (app-page! pages "wmark-pro" wmark-pro-head)
        later {:folder "next-app" :name "Next App" :subtitle "Coming" :about "Not yet." :accent ["#23A982" "#E5A23A"]}
        conf  (config! #(update % :apps conj later) #(assoc-in % [:apps "next-app"] {:about "Pas encore."}))]
    (testing "an app in site.edn with no folder on gh-pages yet is left out, with a warning"
      (let [{:keys [exit said index]} (portal! pages "--config" conf)]
        (is (zero? exit) said)
        (is (str/includes? said "next-app isn't published yet"))
        (is (not (str/includes? index "Next App")))
        (is (str/includes? index "Wmark Pro"))))
    (testing "and listed after its first publication, in site.edn's order"
      (app-page! pages "next-app" "")
      (let [{:keys [exit said index]} (portal! pages "--config" conf)]
        (is (zero? exit) said)
        (is (< (str/index-of index "Wmark Pro") (str/index-of index "Next App")))
        (is (str/includes? index ".app-next-app::before{background:linear-gradient(90deg,#23A982,#E5A23A)}"))))
    (testing "with nothing published, there is no page to make"
      (let [{:keys [exit said index]} (portal! (temp-dir))]
        (is (= 1 exit))
        (is (str/includes? said "No app is published yet"))
        (is (nil? index))))))

(deftest an-icon-is-taken-only-as-a-data-uri
  (doseq [[what head] {"no icon"            ""
                       "an icon elsewhere"  "<link rel=\"icon\" href=\"https://elsewhere.example/icon.svg\">"
                       "a PNG"              "<link rel=\"icon\" href=\"data:image/png;base64,iVBORw0KGgo=\">"
                       "markup in the href" "<link rel=\"icon\" href=\"data:image/svg+xml;base64,PHN2Zy8+<b>\">"}]
    (testing what
      (let [pages (temp-dir)
            _     (app-page! pages "wmark-pro" head)
            {:keys [exit said index]} (portal! pages)]
        (is (zero? exit) said)
        (is (str/includes? said "wmark-pro has no icon in its page"))
        (is (str/includes? index "class=\"app-icon plain\""))
        (is (not (str/includes? index "elsewhere.example")))
        (is (not (str/includes? index "<b>")))))))

(deftest site-edn-is-checked-and-its-words-escaped
  (let [pages (temp-dir)
        _     (app-page! pages "wmark-pro" wmark-pro-head)]
    (doseq [[problem f] {":url must be https"            #(assoc % :url "http://echojustus.github.io/clogem-support/")
                         ":feedback's :repo must be"     #(assoc-in % [:feedback :repo] "javascript:alert(1)")
                         "a :discussion (a category's"   #(assoc-in % [:feedback :channels 0 :issue-form] "bug-report.yml")
                         "its :discussion must be"       #(assoc-in % [:feedback :channels 0 :discussion] "Q&A")
                         "its :issue-form must be"       #(assoc-in % [:feedback :channels 2 :issue-form] "../x.yml")
                         "its :store needs"              #(assoc-in % [:apps 0 :store :url] "http://apps.microsoft.com/")
                         ":commercial needs"             #(update % :commercial dissoc :note)
                         "it needs a :repo (its source)" #(assoc-in % [:open-source :projects 0 :repo] "https://github.com/EchoJustus/clogem-hstry")
                         "its :repo must be a GitHub"    #(assoc-in % [:open-source :projects 1 :repo] "https://example.com/clogem-press")
                         "it names its :license"         #(update-in % [:open-source :projects 1] dissoc :license)
                         "its :icon must be an .svg or"  #(assoc-in % [:open-source :projects 2 :icon] "../../etc/passwd")
                         "own :id"                       #(update-in % [:open-source :projects] (fn [ps] (conj ps (first ps))))
                         "at least one project"          #(assoc-in % [:open-source :projects] [])
                         ":business must be an email"    #(assoc % :business "someone")
                         "its :folder must be one"       #(assoc-in % [:apps 0 :folder] "../wmark-pro")
                         "its :accent must be two"       #(assoc-in % [:apps 0 :accent] ["red" "#EDBE6A"])
                         "it needs :name"                #(assoc-in % [:apps 0 :about] " ")
                         "each have their own :folder"   #(update % :apps (fn [apps] (conj apps (first apps))))
                         ":apps must list at least one"  #(assoc % :apps [])
                         "its :folder is a language's"   #(assoc-in % [:apps 0 :folder] "zh-hans")
                         ":languages starts with en"     #(update % :languages (comp vec reverse))
                         "its :lang must be a language"  #(assoc-in % [:languages 1 :lang] "Chinese")
                         ":ui must give exactly"         #(update % :ui dissoc :browse)}]
      (testing problem
        (let [{:keys [exit said]} (portal! pages "--config" (config! f))]
          (is (= 1 exit))
          (is (str/includes? said problem) said))))
    (testing "words are shown as text, never as markup"
      (let [{:keys [exit index]} (portal! pages "--config" (config! #(assoc-in % [:apps 0 :about] "<script>alert(1)</script> & more")))]
        (is (zero? exit))
        (is (str/includes? index "&lt;script&gt;alert(1)&lt;/script&gt; &amp; more"))
        (is (not (str/includes? index "<script")))))))

(defn- png-bytes
  "The first bytes of a PNG `w` by `h` (signature and IHDR), enough for the
  check."
  [w h]
  (let [u32 (fn [n] [(bit-shift-right n 24) (bit-and (bit-shift-right n 16) 0xff) (bit-and (bit-shift-right n 8) 0xff) (bit-and n 0xff)])]
    (byte-array (map unchecked-byte (concat [0x89 0x50 0x4E 0x47 0x0D 0x0A 0x1A 0x0A 0 0 0 13 0x49 0x48 0x44 0x52]
                                            (u32 w) (u32 h) [8 6 0 0 0])))))

(deftest the-projects-icons-are-plain-drawings
  (testing "each icon site.edn names is in icons/: a plain SVG that runs nothing and reaches nothing outside it, or a square PNG of at least 112 pixels"
    (doseq [{:keys [id icon]} (get-in site [:open-source :projects])]
      (if (str/ends-with? icon ".png")
        (is (nil? (portal/png-problem (fs/read-all-bytes icon))) id)
        (is (nil? (portal/icon-problem (slurp icon))) id))))
  (testing "the redesigned icons (v3) are the PNGs, for clogem-hstry and clogem-press"
    (is (= {"clogem-hstry" "icons/clogem-hstry.png" "clogem-press" "icons/clogem-press.png"}
           (into {} (for [{:keys [id icon]} (get-in site [:open-source :projects]) :when (str/ends-with? icon ".png")] [id icon])))))
  (testing "what makes a PNG unfit"
    (is (nil? (portal/png-problem (png-bytes 128 128))))
    (is (= "it isn't square" (portal/png-problem (png-bytes 128 96))))
    (is (= "it is smaller than 112 pixels" (portal/png-problem (png-bytes 64 64))))
    (is (= "it isn't a PNG" (portal/png-problem (.getBytes "<svg/>" "UTF-8")))))
  (testing "what makes an icon unsafe"
    (doseq [[what svg] {"a script"        "<svg><script>alert(1)</script></svg>"
                        "a handler"       "<svg onload=\"alert(1)\"></svg>"
                        "a link out"      "<svg><use href=\"https://elsewhere.example/a.svg#x\"/></svg>"
                        "an image"        "<svg><image href=\"#x\"/></svg>"
                        "a url() outside" "<svg><rect fill=\"url(https://elsewhere.example/p)\"/></svg>"
                        "not an SVG"      "<html></html>"}]
      (is (some? (portal/icon-problem svg)) what)))
  (testing "references inside the drawing are fine"
    (is (nil? (portal/icon-problem "<svg><rect fill=\"url(#g)\" filter=\"url(#s)\"/><use href=\"#a\"/></svg>"))))
  (testing "a missing or unsafe icon stops the build"
    (let [pages (temp-dir)
          _     (app-page! pages "wmark-pro" wmark-pro-head)]
      (doseq [[said f] {"isn't there"         #(assoc-in % [:open-source :projects 0 :icon] "icons/missing.svg")}]
        (let [{:keys [exit] out :said} (portal! pages "--config" (config! f))]
          (is (= 1 exit))
          (is (str/includes? out said) out))))))

(deftest every-feedback-channel-has-its-form
  (let [{:keys [channels repo]} (:feedback site)]
    (is (= repo "https://github.com/EchoJustus/clogem-support") "the forms below are this repository's")
    (doseq [{:keys [discussion issue-form title]} channels]
      (testing title
        (let [file (if discussion
                     (str ".github/DISCUSSION_TEMPLATE/" discussion ".yml")
                     (str ".github/ISSUE_TEMPLATE/" issue-form))
              form (yaml/parse-string (slurp file))
              body (:body form)]
          (is (seq body) file)
          (is (some #(not= "markdown" (:type %)) body) "a form needs a field")
          (is (some #(str/includes? (str (get-in % [:attributes :value])) "public") body) "it says it's public")
          (is (some #(and (= "checkboxes" (:type %))
                          (some :required (get-in % [:attributes :options])))
                    body)
              "and asks to leave out what shouldn't be")
          (is (apply distinct? (keep :id body)) "field ids unique")
          (when issue-form
            (is (every? (comp not str/blank? str) [(:name form) (:description form)]) "an issue form has a name and description")))))
    (testing "blank issues are off; questions and ideas point to Discussions"
      (let [config (yaml/parse-string (slurp ".github/ISSUE_TEMPLATE/config.yml"))
            urls   (set (map :url (:contact_links config)))]
        (is (false? (:blank_issues_enabled config)))
        (doseq [{:keys [discussion]} channels :when discussion]
          (is (contains? urls (str repo "/discussions/new?category=" discussion))))))))

;; ---------------------------------------------------------------------------
;; Clogem Kai, the text face (README, "Typography"; fonts/README.md)

(def ^:private subsets (edn/read-string (slurp "fonts/subsets.edn")))

(def ^:private kai-ids
  "The pages in Clogem Kai (owner, 2026-10-26): English and Chinese, and the
  404 page, which is English."
  (conj (set (map :id (filter :kai languages))) "404"))

(defn- subset-base64 [key weight]
  (.encodeToString (Base64/getEncoder) ^bytes (fs/read-all-bytes (fs/file "fonts" (str key "-" weight ".woff2")))))

(defn- on-path? [exe] (some #(fs/executable? (fs/file % exe)) (fs/exec-paths)))

(deftest english-and-chinese-are-set-in-clogem-kai
  ;; the owner, 2026-10-26: LXGW WenKai for all text but the headings and
  ;; the heavy labels, in English and Chinese; Portuguese and Spanish in
  ;; the sans
  (is (= #{"en" "zh-hant" "zh-hans" "404"} kai-ids))
  (let [dir (temp-dir)
        _   (app-page! dir "wmark-pro" wmark-pro-head)
        {:keys [pages not-found]} (portal! dir)]
    (doseq [[key html] (assoc pages "404" not-found)
            :let [style (second (re-find #"(?s)<style>(.*?)</style>" html))
                  faces (re-seq #"@font-face\{font-family:\"Clogem Kai\";src:url\(data:font/woff2;base64,([A-Za-z0-9+/=]+)\) format\(\"woff2\"\);font-weight:(\d+);font-style:normal;font-display:swap\}" style)
                  rule  (fn [sel] (second (re-find (re-pattern (str "(?:^|\\n)" (java.util.regex.Pattern/quote sel) "\\{font-family:([^;}]*)")) style)))
                  sans-langs "[lang=\"es-ES\"]:not(html),[lang=\"pt-BR\"]:not(html)"]]
      (testing key
        (if (kai-ids key)
          (do
            (testing "embeds its own two subsets, which the CSP lets in as data: alone"
              (is (= 2 (count faces)))
              (is (= {"400" (subset-base64 key "regular") "700" (subset-base64 key "bold")}
                     (into {} (for [[_ b64 w] faces] [w b64])))))
            (testing "sets its text in Clogem Kai, then the sans, then the page's Chinese fonts"
              (is (re-find #"body\{[^}]*font:400 1\.0625rem/1\.7 \"Clogem Kai\",\"Segoe UI Variable Text\"[^;]*,var\(--cjk\);" style))
              (is (str/starts-with? (rule "[lang]:not(html)") "\"Clogem Kai\",")))
            (testing "and Portuguese and Spanish words (the menu's names, the 404 page's lines) in the sans"
              (is (str/starts-with? (str (rule sans-langs)) "\"Segoe UI Variable Text\""))
              (is (> (str/index-of style sans-langs) (str/index-of style "[lang]:not(html){")) "after the rule it overrides")))
          (testing "Portuguese and Spanish: no Kai, the sans throughout"
            (is (empty? faces))
            (is (not (str/includes? style "Clogem Kai")))
            (is (re-find #"body\{[^}]*font:400 1\.0625rem/1\.7 \"Segoe UI Variable Text\"" style))
            (is (not (fs/exists? (fs/file "fonts" (str key "-regular.woff2")))))))
        (is (str/includes? (str/replace html "&apos;" "'") "; font-src data:; "))
        (testing "headings and heavy labels in the sans"
          (doseq [sel ["h1,h2,h3" ".eyebrow,.chip,.store strong"]]
            (is (some? (rule sel)) sel)
            (is (not (str/includes? (str (rule sel)) "Clogem Kai")) sel)))))))

(deftest every-kai-page-finds-its-glyphs-in-its-subsets
  ;; after a change to the words: bb fonts (fonts/README.md)
  (let [{:keys [languages sites]} (portal/load-site {:config "site/site.edn"})
        pages (portal/pages sites "2026" languages)]
    (is (= kai-ids (set (keys (:pages subsets)))))
    (is (= kai-ids (set (keep (fn [[k p]] (when (:kai? p) k)) pages))))
    (doseq [[key page] pages
            :when (kai-ids key)
            :let [made (get-in subsets [:pages key])]]
      (testing key
        (doseq [[w chars] (portal/needed page)
                :let [lacking (remove (set (get made w)) chars)]]
          (is (empty? lacking) (str (name w) " lacks " (apply str lacking) ": run bb fonts")))
        (doseq [w ["regular" "bold"]
                :let [f (fs/file "fonts" (str key "-" w ".woff2"))]]
          (is (= "wOF2" (String. ^bytes (java.util.Arrays/copyOf ^bytes (fs/read-all-bytes f) 4) StandardCharsets/US_ASCII))
              (str f)))))
    (testing "a page in the sans needs nothing"
      (is (= {:regular #{} :bold #{}} (portal/needed (pages "pt")))))
    (testing "the heavy text and the Portuguese and Spanish words stay out of the subsets, the rest is in one"
      (let [text (portal/page-text (pages "en"))]
        (is (str/includes? (:sans text) (get-in site [:open-source :title])))
        (is (str/includes? (:sans text) "Português"))
        (is (str/includes? (:sans text) "Español"))
        (is (not (re-find #"[êñ]" (str (:regular text) (:bold text)))))
        (is (str/includes? (:regular text) (get-in site [:open-source :lead])))
        (is (str/includes? (:bold text) (get-in site [:ui :source])))))
    (testing "cut from the pinned LXGW WenKai, for Traditional Chinese too"
      (is (= (into {} (for [[w f] (:files fonts/source)] [w (:sha256 f)])) (get-in subsets [:source :sha256])))
      (is (= "LXGW WenKai" (get-in subsets [:source :name]))))))

(deftest the-subsets-go-by-their-own-name-and-hold-their-glyphs
  ;; SIL Open Font License 1.1: a modified version doesn't carry a Reserved
  ;; Font Name ("LXGW", "霞鹜"); the copyright and the licence stay
  (if-not (on-path? "ttx")
    (println "Skipping the subsets' names and glyphs: no ttx on PATH (pip install fonttools brotli)")
    (doseq [f (fs/glob "fonts" "*.woff2")
            :let [ttx  #(:out (p/shell {:out :string :err :string} "ttx" "-q" "-t" % "-o" "-" (str f)))
                  records (into {} (for [[_ id v] (re-seq #"(?s)<namerecord nameID=\"(\d+)\"[^>]*>\s*(.*?)\s*</namerecord>" (ttx "name"))]
                                     [(parse-long id) v]))
                  mapped (set (map #(Long/decode (second %)) (re-seq #"<map code=\"(0x[0-9a-f]+)\"" (ttx "cmap"))))
                  [_ key w] (re-matches #"(.*)-(regular|bold)\.woff2" (str (fs/file-name f)))
                  style (if (= w "bold") "Bold" "Regular")]]
      (testing (str f)
        (is (= #{0 1 2 4 6 13 14} (set (keys records))))
        (is (= ["Clogem Kai" style (str "Clogem Kai " style) (str "ClogemKai-" style)] (map records [1 2 4 6])))
        (is (re-find #"^Copyright 20\d\d" (records 0)))
        (is (str/includes? (records 13) "SIL Open Font License, Version 1.1"))
        (let [lacking (remove #(mapped (long (int %))) (get-in subsets [:pages key (keyword w)]))]
          (is (empty? lacking) (str "no glyph for " (apply str lacking))))))))

;; ---------------------------------------------------------------------------
;; The workflow

(def ^:private workflow-text (slurp ".github/workflows/portal.yml"))
(def ^:private workflow (yaml/parse-string workflow-text))

(defn- step [job id] (some #(when (= id (:id %)) %) (get-in workflow [:jobs job :steps])))

(defn- git! [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true}
                 "git" "-c" "user.name=Test" "-c" "user.email=test@example.invalid" args)]
    (when-not (zero? (:exit r)) (throw (ex-info (str "git " (str/join " " args) ": " (:err r)) r)))
    (str/trim (:out r))))

(defn- stand-in!
  "A bare repository standing in for this one, its gh-pages holding two apps'
  folders and a file at the root that isn't the portal's; and the
  workflow's working folder: this repository's scripts, words and fonts, and a
  clone of gh-pages in pages/, as actions/checkout leaves them."
  []
  (let [root   (temp-dir)
        remote (str (fs/path root "remote.git"))
        seed   (str (fs/path root "seed"))
        work   (str (fs/path root "work"))]
    (git! root "init" "--quiet" "--bare" "--initial-branch" "gh-pages" remote)
    (git! root "init" "--quiet" "--initial-branch" "gh-pages" seed)
    (app-page! seed "wmark-pro" wmark-pro-head)
    (app-page! seed "other-app" "")
    (spit (fs/file seed "CNAME.txt") "not the portal's\n")
    (git! seed "add" "--all")
    (git! seed "commit" "--quiet" "-m" "Two apps")
    (git! seed "push" "--quiet" remote "gh-pages")
    (fs/create-dirs work)
    (doseq [d ["scripts" "site" "icons" "fonts"]] (fs/copy-tree d (fs/path work d)))
    (git! work "clone" "--quiet" "--branch" "gh-pages" remote "pages")
    {:remote remote :seed seed :work work}))

(defn- publish! [work]
  (p/shell {:dir work :out :string :err :string :continue true
            :extra-env {"OWNED" (get-in workflow [:jobs :publish :env :OWNED])}}
           "bash" "-c" (:run (step :publish "publish"))))

(defn- tree [remote] (set (str/split-lines (git! remote "ls-tree" "-r" "--name-only" "gh-pages"))))

(deftest the-workflow-publishes-the-root-files-alone
  (let [{:keys [remote seed work]} (stand-in!)
        before (git! remote "rev-parse" "gh-pages")
        app    (fn [] (git! remote "show" "gh-pages:wmark-pro/index.html"))
        page   (app)]
    (testing "the first publication adds the three root files, and nothing else changes"
      (let [r (publish! work)]
        (is (zero? (:exit r)) (str (:out r) (:err r)))
        (is (= (into #{"index.html" "404.html" ".nojekyll" "wmark-pro/index.html" "other-app/index.html" "CNAME.txt"}
                     (for [{:keys [id]} (rest languages)] (str id "/index.html")))
               (tree remote)))
        (is (= page (app)))
        (is (= "not the portal's" (str/trim (git! remote "show" "gh-pages:CNAME.txt"))))
        (is (= before (git! remote "rev-parse" "gh-pages~1")) "one commit, on top of the branch")
        (is (= "Publish the support hub's front page" (git! remote "log" "-1" "--format=%s" "gh-pages")))
        (is (str/includes? (git! remote "show" "gh-pages:index.html") "href=\"wmark-pro/\""))))
    (testing "a rerun with nothing new pushes nothing"
      (let [tip (git! remote "rev-parse" "gh-pages")
            r   (publish! work)]
        (is (zero? (:exit r)) (str (:out r) (:err r)))
        (is (str/includes? (:out r) "unchanged"))
        (is (= tip (git! remote "rev-parse" "gh-pages")))))
    (testing "an app that published meanwhile keeps its page, and a refused push is tried again, never by force"
      (git! seed "pull" "--quiet" remote "gh-pages")
      (spit (fs/file seed "wmark-pro" "index.html") "<!doctype html>\n<html><head></head><body>newer</body></html>\n")
      (git! seed "commit" "--quiet" "-am" "Publish the wmark-pro support page")
      (git! seed "push" "--quiet" remote "gh-pages")
      (let [hook (fs/file remote "hooks" "pre-receive")
            newer (git! remote "rev-parse" "gh-pages")]
        (spit hook "#!/bin/sh\nif [ ! -f refused-once ]; then touch refused-once; echo 'refused for the test' >&2; exit 1; fi\n")
        (fs/set-posix-file-permissions hook "rwxr-xr-x")
        (let [r (publish! work)]
          (is (zero? (:exit r)) (str (:out r) (:err r)))
          (is (str/includes? (:out r) "attempt 1 of 3"))
          (is (fs/exists? (fs/file remote "refused-once")))
          (is (= newer (git! remote "rev-parse" "gh-pages~1")))
          (is (str/includes? (app) "newer"))
          (testing "the icon gone from the app's page, its card shows the plain mark"
            (is (str/includes? (git! remote "show" "gh-pages:index.html") "app-icon plain"))))))))

(deftest the-workflow-text
  (testing "runs on its sources on main, after every Pages build, by hand, and tests pull requests"
    (doseq [s ["  push:\n    branches: [main]" "  page_build:" "  workflow_dispatch:" "  pull_request:"]]
      (is (str/includes? workflow-text s) s)))
  (testing "reads by default; only the publishing job writes, to contents and Pages"
    (is (= {:contents "read"} (:permissions workflow)))
    (is (= {:contents "write" :pages "write"} (get-in workflow [:jobs :publish :permissions])))
    (is (nil? (get-in workflow [:jobs :test :permissions])))
    (is (= "github.event_name != 'pull_request' && github.ref == 'refs/heads/main'"
           (get-in workflow [:jobs :publish :if])))
    (is (= ["test"] (let [n (get-in workflow [:jobs :publish :needs])] (if (string? n) [n] n)))))
  (testing "stages what the front page owns alone, and never forces"
    (let [run (:run (step :publish "publish"))]
      (is (str/includes? run "git -C pages add -- $OWNED"))
      (is (not (re-find #"add\s+(--all|-A|\.)" run)))
      (is (not (re-find #"--force|\s-f\s|\+HEAD|push[^\n]*\+" run)))
      (is (str/includes? run "for attempt in 1 2 3"))))
  (testing "asks Pages for a build, since its own commits start none"
    (is (str/includes? workflow-text "gh api -X POST \"repos/$GITHUB_REPOSITORY/pages/builds\""))
    (is (str/includes? workflow-text "GH_TOKEN: ${{ github.token }}")))
  (testing "what it owns is what bb portal writes: the root files and the languages' folders"
    (let [pages (temp-dir)
          _     (app-page! pages "wmark-pro" wmark-pro-head)
          {:keys [out]} (portal! pages)]
      (is (= (set (map str (fs/list-dir out)))
             (set (map #(str (fs/path out %)) (str/split (get-in workflow [:jobs :publish :env :OWNED]) #" ")))))
      (is (= (into ["index.html" "404.html" ".nojekyll"] (map :id (rest languages)))
             (str/split (get-in workflow [:jobs :publish :env :OWNED]) #" ")))))
  (testing "the address it checks is site.edn's"
    (is (= (:url site) (get-in workflow [:jobs :publish :env :URL]))))
  (testing "every action pinned by commit"
    (let [uses (map second (re-seq #"uses:\s*(\S+)" workflow-text))]
      (is (seq uses))
      (is (every? #(re-find #"@[0-9a-f]{40}$" %) uses) (pr-str uses)))))

;; ---------------------------------------------------------------------------
;; The license (README, "License")

(deftest every-file-says-what-covers-it
  (testing "code: MIT, in its first lines"
    (let [code (concat (fs/glob "scripts" "**") (fs/glob "test" "**")
                       (fs/glob ".github" "**" {:hidden true})
                       [(fs/path "bb.edn") (fs/path "deps.edn") (fs/path ".gitignore")])]
      (is (seq code))
      (doseq [f code :when (fs/regular-file? f)]
        (is (re-find #"\A(?:;;|#) Copyright 2026 EchoJustus\. Part of clogem-support\.\n(?:;;|#) SPDX-License-Identifier: MIT\n"
                     (slurp (str f)))
            (str f)))))
  (testing "the projects' icons: each carries its own licence (icons/README.md)"
    (is (str/includes? (slurp "icons/clogem-wmark.svg") "SPDX-License-Identifier: EPL-2.0"))
    (let [readme (slurp "icons/README.md")]
      (is (re-find #"`clogem-press\.png`[^\n]*EPL-2\.0" readme) "a PNG carries no header: the README states its licence")
      (is (re-find #"`clogem-hstry\.png`[^\n]*all rights reserved" readme))
      (testing "the hub's own mark: all rights reserved, in its first line and in the README"
        (doseq [f ["icons/clogem-support.svg" "icons/clogem-support-favicon.svg"]]
          (is (str/starts-with? (slurp f) "<!-- Copyright 2026 EchoJustus. All rights reserved. Part of clogem-support. -->") f)
          (is (re-find (re-pattern (str "`" (str/replace (str (fs/file-name f)) "." "\\.") "`[^\\n]*all rights reserved")) readme) f))))
    (is (str/starts-with? (slurp "icons/EPL-2.0.txt") "Eclipse Public License - v 2.0"))
    (is (str/starts-with? (slurp "icons/README.md") "<!-- Copyright 2026 EchoJustus. All rights reserved.")))
  (testing "the words: all rights reserved, outside the MIT license"
    (doseq [f (fs/glob "site" "**") :when (fs/regular-file? f)]
      (is (str/starts-with? (slurp (str f)) ";; Copyright 2026 EchoJustus. All rights reserved.") (str f))))
  (testing "the trailers: all rights reserved, their third-party parts under their own licences"
    (doseq [f (fs/glob "trailers" "*/*.edn")]
      (is (str/starts-with? (slurp (str f)) ";; Copyright 2026 EchoJustus. All rights reserved.") (str f)))
    (doseq [f (fs/glob "trailers" "*/captions/*.vtt")]
      (is (str/includes? (slurp (str f)) "\nCopyright 2026 EchoJustus. All rights reserved.\n") (str f)))
    (doseq [d (fs/glob "trailers" "*" {:max-depth 1}) :when (fs/directory? d)]
      (let [readme (slurp (str (fs/path d "README.md")))]
        (is (str/includes? readme "All rights reserved") (str d))
        ;; the footage's own terms: public domain, or a Creative Commons licence
        (is (re-find #"public domain|CC BY" readme) (str d))))
    (doseq [fonts (fs/glob "trailers" "*/sources/fonts")]
      (is (fs/exists? (fs/path fonts "OFL.txt")) "the fonts travel with their licence")))
  (testing "the font: the SIL Open Font License, with its copyright and Reserved Font Names"
    (let [ofl (slurp "fonts/OFL-LXGW-WenKai.txt")]
      (is (str/starts-with? ofl "Copyright 2021-2023 LXGW (https://github.com/lxgw/LxgwWenKai), with Reserved Font Names \"霞鹜\" and \"LXGW\"."))
      (is (str/includes? ofl "SIL OPEN FONT LICENSE Version 1.1 - 26 February 2007")))
    (is (= #{"OFL-LXGW-WenKai.txt" "README.md" "subsets.edn"}
           (set (remove #(str/ends-with? % ".woff2") (map (comp str fs/file-name) (fs/list-dir "fonts"))))))
    (is (str/starts-with? (slurp "fonts/README.md") "<!-- Copyright 2026 EchoJustus. All rights reserved."))
    (is (str/starts-with? (slurp "fonts/subsets.edn") ";; Made by bb fonts")))
  (testing "LICENSE is the MIT text as it stands, so GitHub recognises it"
    (let [text (slurp "LICENSE")]
      (is (str/starts-with? text "MIT License\n\nCopyright (c) 2026 EchoJustus\n\nPermission is hereby granted, free of charge,"))
      (is (str/ends-with? text "OTHER DEALINGS IN THE\nSOFTWARE.\n"))))
  (testing "the maintainers' rules: all rights reserved, outside the MIT license"
    (doseq [f ["CLAUDE.md" "AGENTS.md"]]
      (is (str/starts-with? (slurp f) "<!-- Copyright 2026 EchoJustus. All rights reserved. Part of clogem-support. -->") f)))
  (testing "the README draws the line"
    (let [readme (slurp "README.md")]
      (is (str/includes? readme "## License"))
      (is (str/includes? readme "`trailers/`"))
      (is (re-find #"public domain|CC BY" readme) "the footage's terms")
      (is (str/includes? readme "SIL Open Font License"))
      (is (str/includes? readme "`fonts/`")))))
