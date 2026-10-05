;; Copyright 2026 EchoJustus. Part of clogem-support.
;; SPDX-License-Identifier: MIT
(ns trailer.main
  "bb trailer: a Store trailer from its take, its edit list and its words.

    bb trailer [--trailer trailers/wmark-pro] [--sources DIR] [--quality repo|store]
               [--only captions] [--work DIR]

  The trailer's folder holds edit.edn and words.edn, and its sources/
  (or --sources DIR): the take and its marks (trailer.record), the videos
  the app rendered in it, icon.png and fonts/. It gets the results:

    <name>-trailer.mp4                  the trailer, to the Store's MP4 recipe
    <name>-trailer-thumbnail.png        a 1920x1080 still of its title card
    captions/<name>-trailer.<lang>.vtt  one caption file per listing language

  --quality store encodes at the recipe's 50 Mbps into
  <name>-trailer-store.mp4 instead (about 6 MB a second: too large for git,
  and ignored by it); repo, the default, follows the same recipe at a
  smaller bitrate. --only captions writes the caption files alone."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [trailer.captions :as captions]
            [trailer.check :as check]
            [trailer.edit :as edit]
            [trailer.music :as music]))

(defn- fail! [& msg] (binding [*out* *err*] (println (str/join " " msg))) (System/exit 1))

(defn- opts [args]
  (loop [[a b & more :as as] args out {}]
    (cond (empty? as) out
          (str/starts-with? a "--") (recur more (assoc out (keyword (subs a 2)) b))
          :else (fail! "bb trailer [--trailer DIR] [--sources DIR] [--quality repo|store] [--only captions] [--work DIR], not" a))))

(defn word-files!
  "The English words, one text file each, for FFmpeg's textfile=. A
  caption's line breaks become spaces on screen."
  [words dir]
  (fs/create-dirs dir)
  (into {} (for [[id by-lang] words :let [en (get by-lang "en")] :when en]
             (let [f (fs/file dir (str (name id) ".txt"))]
               (spit f (str/replace en "\n" " "))
               [id (str f)]))))

(defn plan
  "What the trailer in folder `trailer` is made of: its edit list, words,
  marks, timeline and cards."
  [trailer sources]
  (let [edl   (edn/read-string (slurp (fs/file trailer "edit.edn")))
        spec  (edn/read-string (slurp (fs/file trailer "words.edn")))
        marks (edn/read-string (slurp (fs/file sources "take.marks.edn")))]
    {:edl edl :spec spec :marks marks
     :timeline (edit/timeline marks edl) :cards (edit/cards marks edl)}))

(defn write-captions!
  "The caption files, one per language; their paths."
  [trailer {:keys [spec timeline cards]}]
  (let [cues (captions/cues spec timeline cards)
        name (fs/file-name trailer)]
    (doall (for [lang (:languages spec)]
             (let [f (fs/file trailer "captions" (str name "-trailer." lang ".vtt"))]
               (fs/create-dirs (fs/parent f))
               (spit f (captions/vtt spec lang cues))
               (str f))))))

(defn- captions-problems [vtts length]
  (concat (mapcat check/captions-problems vtts)
          (mapcat #(map (fn [p] (str (fs/file-name %) ": " p)) (captions/problems (slurp %) length)) vtts)))

(defn- build-all!
  [trailer sources quality work name {:keys [edl spec marks cards] tl :timeline :as made}]
  (let [src     (fn [f] (let [p (fs/file sources f)] (when-not (fs/exists? p) (fail! "Missing source" (str p))) (str p)))
        ;; the edit list names its sources: {:orchard "orchard_aerial_wm.mp4" ...}
        files   (into {:take (src "take.mkv") :icon (src "icon.png")}
                      (for [[id f] (:sources edl)] [id (src f)]))
        edl     (assoc edl :fonts {:regular (src "fonts/OpenSans-400.ttf") :semibold (src "fonts/OpenSans-600.ttf")
                                   :bold (src "fonts/OpenSans-700.ttf")})
        words   (word-files! (:words spec) (fs/file work "words"))
        _       (println (format "Cutting %d segments, %.2f s in all" (count (:segments edl)) (:length tl)))
        segs    (doall (map-indexed (fn [i seg] (edit/render-segment! marks edl files words seg
                                                                       (str (fs/file work (format "seg-%02d.mp4" i)))))
                                    (:segments edl)))
        _       (println "Composing the soundtrack")
        wav     (music/render! (:length tl) (str (fs/file work "music.wav")))
        out     (str (fs/file trailer (str name (if (= quality :store) "-trailer-store.mp4" "-trailer.mp4"))))
        _       (println "Encoding" out (str "(" (clojure.core/name quality) ")"))
        _       (edit/encode! segs (map :length (:segments tl)) (:crossfade edl) wav out quality)
        title   (some #(when (= :title (:id %)) %) (:segments tl))
        thumb   (edit/thumbnail! out (+ (:start title) (/ (:length title) 2)) (str (fs/file trailer (str name "-trailer-thumbnail.png"))))
        vtts    (write-captions! trailer made)
        report  (check/video-problems out)
        errors  (concat (:errors report) (check/thumbnail-problems thumb) (captions-problems vtts (:length tl)))]
    (doseq [w (:warnings report)] (println "Note:" w))
    (println (format "Wrote %s (%.1f MB, %.2f s), %s and %d caption files"
                     out (/ (fs/size out) 1e6) (:length tl) (fs/file-name thumb) (count vtts)))
    (when (seq errors)
      (fail! (str "The trailer doesn't meet the Store's rules:\n  " (str/join "\n  " errors))))))

(defn build!
  [{:keys [sources trailer quality work only] :or {trailer "trailers/wmark-pro" quality "repo"}}]
  (let [sources (or sources (str (fs/path trailer "sources")))
        quality (keyword quality)
        _       (when-not (contains? edit/encodings quality) (fail! "--quality is repo or store"))
        name    (fs/file-name trailer)
        work    (or work (str (fs/path "target" "trailer" name)))
        {:keys [edl spec marks] tl :timeline :as made} (plan trailer sources)]
    (if (= only "captions")
      (let [vtts (write-captions! trailer made)]
        (when-let [ps (seq (captions-problems vtts (:length tl)))] (fail! (str/join "\n" ps)))
        (println "Wrote" (count vtts) "caption files"))
      (build-all! trailer sources quality work name made))))

(defn -main [& args] (build! (opts args)))
