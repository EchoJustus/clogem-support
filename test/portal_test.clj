;; Copyright 2026 EchoJustus. Part of clogem-support.
;; SPDX-License-Identifier: MIT
(ns portal-test
  "bb portal (scripts/portal.clj): the hub's front page and its 404 page,
  built from site/site.edn and the apps' folders on gh-pages; and
  .github/workflows/portal.yml, whose publishing step is run here against a
  local repository standing in for gh-pages."
  (:require [babashka.fs :as fs]
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

(defn- config!
  "site.edn with `f` applied, written to a file of its own."
  [f]
  (let [file (fs/file (temp-dir) "site.edn")]
    (spit file (pr-str (f site)))
    (str file)))

(defn- portal!
  "Runs `bb portal`; its exit, its words and what it wrote."
  [site-dir & args]
  (let [out (temp-dir)
        r   (apply p/shell {:out :string :err :string :continue true}
                   "bb" "scripts/portal.clj" "--site-dir" (str site-dir) "--out" out args)
        read (fn [n] (let [f (fs/file out n)] (when (fs/exists? f) (slurp f))))]
    {:exit (:exit r) :said (str (:out r) (:err r)) :out out
     :index (read "index.html") :not-found (read "404.html") :nojekyll (read ".nojekyll")}))

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
                                               (subs index (str/index-of index "id=\"open-source\"") (str/index-of index "id=\"apps\"")))))
            "every project's icon, embedded")))
    (testing "open source first, then the commercial apps with the note about Pro editions to come"
      (is (< (str/index-of index "id=\"open-source\"") (str/index-of index "id=\"apps\"") (str/index-of index "id=\"feedback\"")))
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

(deftest the-pages-run-nothing-and-load-nothing-from-elsewhere
  (let [pages (temp-dir)
        _     (app-page! pages "wmark-pro" wmark-pro-head)
        {:keys [index not-found]} (portal! pages)]
    (doseq [[n html] {"index.html" index "404.html" not-found}]
      (testing n
        (is (not (str/includes? (str/lower-case html) "<script")))
        (is (not (re-find #"(?i)\son[a-z]+=" html)) "no event handlers")
        (is (not (re-find #"\sstyle=" html)) "no style attributes: the CSP would block them")
        (is (every? #(str/starts-with? % "data:") (map second (re-seq #"\ssrc=\"([^\"]*)\"" html)))
            "images are data: URIs")
        (is (not (re-find #"<link [^>]*rel=\"stylesheet\"" html)))
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
  ;; on both pages; Auto (the system's setting) is chosen when a page opens
  (let [pages (temp-dir)
        _     (app-page! pages "wmark-pro" wmark-pro-head)
        {:keys [index not-found]} (portal! pages)]
    (doseq [[n html] {"index.html" index "404.html" not-found}]
      (testing n
        (let [radios (re-seq #"<input [^>]*name=\"theme\"[^>]*>" html)
              style  (second (re-find #"(?s)<style>(.*?)</style>" html))]
          (is (= ["theme-system" "theme-light" "theme-dark"] (map #(second (re-find #"id=\"([^\"]+)\"" %)) radios)))
          (is (every? #(str/includes? % "type=\"radio\"") radios))
          (is (= ["theme-system"] (keep #(when (str/includes? % " checked") (second (re-find #"id=\"([^\"]+)\"" %))) radios))
              "Auto when the page opens")
          (doseq [id ["system" "light" "dark"]]
            (is (str/includes? html (str "<label for=\"theme-" id "\""))))
          (is (re-find #"<fieldset class=\"theme\"><legend>Colour theme</legend>" html) "named for screen readers")
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

(deftest an-app-is-listed-once-it-is-published
  (let [pages (temp-dir)
        _     (app-page! pages "wmark-pro" wmark-pro-head)
        later {:folder "next-app" :name "Next App" :subtitle "Coming" :about "Not yet." :accent ["#23A982" "#E5A23A"]}
        conf  (config! #(update % :apps conj later))]
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
                         ":apps must list at least one"  #(assoc % :apps [])}]
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
  workflow's working folder: this repository's scripts and words, and a
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
    (doseq [d ["scripts" "site" "icons"]] (fs/copy-tree d (fs/path work d)))
    (git! work "clone" "--quiet" "--branch" "gh-pages" remote "pages")
    {:remote remote :seed seed :work work}))

(defn- publish! [work]
  (p/shell {:dir work :out :string :err :string :continue true}
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
        (is (= #{"index.html" "404.html" ".nojekyll" "wmark-pro/index.html" "other-app/index.html" "CNAME.txt"}
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
  (testing "stages the root files alone and never forces"
    (let [run (:run (step :publish "publish"))]
      (is (str/includes? run "git -C pages add -- index.html 404.html .nojekyll"))
      (is (not (re-find #"add\s+(--all|-A|\.)" run)))
      (is (not (re-find #"--force|\s-f\s|\+HEAD|push[^\n]*\+" run)))
      (is (str/includes? run "for attempt in 1 2 3"))))
  (testing "asks Pages for a build, since its own commits start none"
    (is (str/includes? workflow-text "gh api -X POST \"repos/$GITHUB_REPOSITORY/pages/builds\""))
    (is (str/includes? workflow-text "GH_TOKEN: ${{ github.token }}")))
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
      (is (re-find #"`clogem-hstry\.png`[^\n]*all rights reserved" readme)))
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
        (is (str/includes? readme "CC BY 3.0") (str d))))
    (doseq [fonts (fs/glob "trailers" "*/sources/fonts")]
      (is (fs/exists? (fs/path fonts "OFL.txt")) "the fonts travel with their licence")))
  (testing "LICENSE is the MIT text as it stands, so GitHub recognises it"
    (let [text (slurp "LICENSE")]
      (is (str/starts-with? text "MIT License\n\nCopyright (c) 2026 EchoJustus\n\nPermission is hereby granted, free of charge,"))
      (is (str/ends-with? text "OTHER DEALINGS IN THE\nSOFTWARE.\n"))))
  (testing "the README draws the line"
    (let [readme (slurp "README.md")]
      (is (str/includes? readme "## License"))
      (is (str/includes? readme "`trailers/`"))
      (is (str/includes? readme "CC BY 3.0"))
      (is (str/includes? readme "SIL Open Font License")))))
