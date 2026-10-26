;; Copyright 2026 EchoJustus. Part of clogem-support.
;; SPDX-License-Identifier: MIT
(ns fonts
  "Clogem Kai, the text face of the front page's English and Chinese pages
  (owner, 2026-10-26): for each such page, the glyphs of LXGW WenKai it
  sets, in two weights, as WOFF2 in fonts/ for bb portal to embed (the
  pages load nothing from other sites). Portuguese and Spanish keep the
  sans, so they have none.

    bb fonts                  every Kai page's subsets, and fonts/subsets.edn
    bb fonts --main DIR       LXGW WenKai from DIR (default: where Ubuntu's
                              fonts-lxgw-wenkai package puts it)

  Traditional Chinese takes LXGW WenKai too (owner, 2026-10-26). The
  source is pinned by its SHA-256: Ubuntu's package (apt install
  fonts-lxgw-wenkai).

  Each subset holds the characters its page sets in that weight
  (portal/needed, from the page's own text) and printable ASCII, so a small
  edit in Latin letters still finds its glyphs. bb test fails when a page
  sets a character its subset lacks: run this again then. The subsets
  carry the font's copyright and licence but not its names, which the
  licence reserves for unmodified fonts (SIL Open Font License 1.1,
  Reserved Font Names \"LXGW\" and \"霞鹜\"): they are named Clogem Kai,
  and the pages call them that.

  It needs fonttools (pyftsubset, ttx and fonttools), with brotli for WOFF2
  (pip install fonttools brotli)."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [portal]))

(set! *warn-on-reflection* true)

(def source
  "The font the subsets are cut from, each weight pinned by its SHA-256."
  {:name    "LXGW WenKai"
   :version "1.315"
   :from    "Ubuntu's package fonts-lxgw-wenkai 1.315+repack-1 (noble), rebuilt from lxgw/LxgwWenKai's source"
   :licence "SIL Open Font License 1.1, Reserved Font Names \"LXGW\" and \"霞鹜\""
   :files   {:regular {:file "LXGWWenKai-Regular.ttf"
                       :sha256 "6cbc11b78b741b661930569c92d03e7926c29b66af7a10da1af355baa2acef9c"}
             :bold    {:file "LXGWWenKai-Bold.ttf"
                       :sha256 "a2692a0c0dac9c954a17789ec3509c6ff0c42a007d43438e5a9816782b1a45f5"}}})

(def ^:private ascii
  "Printable ASCII, in every subset."
  (apply str (map char (range 0x20 0x7F))))

(defn- fail! [& msg] (binding [*out* *err*] (println (str/join " " msg))) (System/exit 1))

(defn- digest-of [f]
  (let [d (.digest (java.security.MessageDigest/getInstance "SHA-256") ^bytes (fs/read-all-bytes f))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) d))))

(defn- source-file
  "A weight of LXGW WenKai from `main-dir`, checked against its pin."
  [weight main-dir]
  (let [{:keys [file sha256]} (get-in source [:files weight])
        f (fs/file main-dir file)]
    (when-not (fs/exists? f)
      (fail! (str f " isn't there: apt install fonts-lxgw-wenkai, or --main DIR")))
    (when-not (= sha256 (digest-of f))
      (fail! (str f " isn't the pinned " (:name source) " " (:version source) " (SHA-256 " (digest-of f) ")")))
    (str f)))

(defn- sh! [& cmd]
  (let [r (apply p/shell {:out :string :err :string :continue true} cmd)]
    (when-not (zero? (:exit r))
      (fail! (first cmd) "failed (pip install fonttools brotli):" (:err r)))))

(def ^:private style-name {:regular "Regular" :bold "Bold"})

(defn own-names
  "The names a subset goes by, as name records for ttx: family Clogem Kai
  (1, 4, 6) in `weight`'s style (2), never the source's."
  [weight]
  (let [style (style-name weight)]
    (apply str (for [[id v] [[1 portal/kai-family] [2 style] [4 (str portal/kai-family " " style)]
                             [6 (str (str/replace portal/kai-family " " "") "-" style)]]]
                 (str "    <namerecord nameID=\"" id "\" platformID=\"3\" platEncID=\"1\" langID=\"0x409\">"
                      v "</namerecord>\n")))))

(defn- subset!
  "pyftsubset `src` to the characters `text` (and ASCII), as WOFF2 at `out`:
  of the source's names only its copyright and licence (0, 13, 14), and
  Clogem Kai's own names, written in with ttx."
  [src text weight out]
  (let [dir   (fs/create-temp-dir)
        chars (str (fs/file dir "chars.txt"))
        sub   (str (fs/file dir "subset.ttf"))
        names (str (fs/file dir "name.ttx"))
        named (str (fs/file dir "named.ttf"))]
    (spit chars (str ascii text))
    (sh! "pyftsubset" src (str "--text-file=" chars) (str "--output-file=" sub)
         "--no-hinting" "--desubroutinize" "--name-IDs=0,13,14" "--drop-tables+=meta,DSIG")
    (sh! "ttx" "-q" "-t" "name" "-o" names sub)
    (spit names (str/replace-first (slurp names) "</name>" (str (own-names weight) "  </name>")))
    (sh! "ttx" "-q" "-m" sub "-o" named names)
    (fs/delete-if-exists out)
    (sh! "fonttools" "ttLib.woff2" "compress" "-o" out named)
    (fs/delete-tree dir)))

(defn -main [& args]
  (let [opts     (apply hash-map args)
        main-dir (get opts "--main" "/usr/share/fonts/truetype/lxgw-wenkai")
        out      (get opts "--out" "fonts")
        {:keys [languages sites]} (portal/load-site {:config "site/site.edn"})
        year     (str (java.time.Year/now))
        pages    (filter (comp :kai? val) (portal/pages sites year languages))]
    (doseq [exe ["pyftsubset" "ttx" "fonttools"]
            :when (not-any? #(fs/executable? (fs/file % exe)) (fs/exec-paths))]
      (fail! exe "isn't on PATH: pip install fonttools brotli"))
    (fs/create-dirs out)
    ;; a page no longer in Kai keeps no subsets
    (doseq [f (fs/glob out "*.woff2")] (fs/delete f))
    (let [made (into (sorted-map)
                     (for [[key page] pages
                           :let [need (portal/needed page)]]
                       (do (doseq [w [:regular :bold]]
                             (subset! (source-file w main-dir) (apply str (need w)) w
                                      (str (fs/file out (str key "-" (name w) ".woff2")))))
                           (println (format "%-8s %d regular and %d bold characters, %,d and %,d bytes"
                                            key (count (need :regular)) (count (need :bold))
                                            (fs/size (fs/file out (str key "-regular.woff2")))
                                            (fs/size (fs/file out (str key "-bold.woff2")))))
                           [key {:regular (apply str (sort (set (str ascii (apply str (need :regular))))))
                                 :bold    (apply str (sort (set (str ascii (apply str (need :bold))))))}])))]
      (spit (fs/file out "subsets.edn")
            (str ";; Made by bb fonts (scripts/fonts.clj): don't edit. What each page's\n"
                 ";; subsets of Clogem Kai hold, by page and weight, and the font they\n"
                 ";; were cut from (fonts/README.md).\n"
                 (with-out-str
                   (pprint/pprint {:source (-> (select-keys source [:name :version :from :licence])
                                               (assoc :sha256 (into {} (for [[w f] (:files source)] [w (:sha256 f)]))))
                                   :pages made}))))
      (println (str "Wrote " out "/: " (count made) " pages' subsets and subsets.edn")))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
