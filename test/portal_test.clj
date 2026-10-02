;; Copyright 2026 EchoJustus. Part of clogem-support.
;; SPDX-License-Identifier: MIT
(ns portal-test
  "bb portal (scripts/portal.clj): the hub's front page and its 404 page,
  built from site/site.edn and the apps' folders on gh-pages; and
  .github/workflows/portal.yml, whose publishing step is run here against a
  local repository standing in for gh-pages."
  (:require [babashka.fs :as fs]
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
    (testing "where to turn next: public issues, the business address"
      (is (str/includes? index (str "href=\"" (:issues site) "\"")))
      (is (str/includes? index (str "href=\"mailto:" (:business site) "?subject=Clogem\"")))
      (is (str/includes? index "Issues are public")))
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
                         ":issues must be an https"      #(assoc % :issues "javascript:alert(1)")
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
    (doseq [d ["scripts" "site"]] (fs/copy-tree d (fs/path work d)))
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
                       [(fs/path "bb.edn") (fs/path ".gitignore")])]
      (is (seq code))
      (doseq [f code :when (fs/regular-file? f)]
        (is (re-find #"\A(?:;;|#) Copyright 2026 EchoJustus\. Part of clogem-support\.\n(?:;;|#) SPDX-License-Identifier: MIT\n"
                     (slurp (str f)))
            (str f)))))
  (testing "the words: all rights reserved, outside the MIT license"
    (doseq [f (fs/glob "site" "**") :when (fs/regular-file? f)]
      (is (str/starts-with? (slurp (str f)) ";; Copyright 2026 EchoJustus. All rights reserved.") (str f))))
  (testing "LICENSE is the MIT text as it stands, so GitHub recognises it"
    (let [text (slurp "LICENSE")]
      (is (str/starts-with? text "MIT License\n\nCopyright (c) 2026 EchoJustus\n\nPermission is hereby granted, free of charge,"))
      (is (str/ends-with? text "OTHER DEALINGS IN THE\nSOFTWARE.\n"))))
  (testing "the README draws the line"
    (is (str/includes? (slurp "README.md") "## License"))))
