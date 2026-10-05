;; Copyright 2026 EchoJustus. Part of clogem-support.
;; SPDX-License-Identifier: MIT
(ns trailer.footage
  "The clips a take watermarks, cut from open footage:

    bb trailer-footage [--trailer trailers/wmark-pro] [--out DIR]

  reads the trailer's footage.edn, downloads each source once with curl
  (into target/footage), checks it against its SHA-256 and cuts the clips into
  DIR (default target/footage/clips): the parts joined, cropped, scaled and
  retimed where the list says, H.264 and silent (the trailer's sound is its
  own music), ready to add to the app."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.security MessageDigest)))

(defn sha256 [file]
  (let [md (MessageDigest/getInstance "SHA-256")
        buf (byte-array 65536)]
    (with-open [in (io/input-stream (fs/file file))]
      (loop [] (let [n (.read in buf)] (when (pos? n) (.update md buf 0 n) (recur)))))
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md)))))

(defn file-name
  "The download's file name: its URL's last part, percent-decoded."
  [url]
  (java.net.URLDecoder/decode ^String (last (str/split url #"/")) "UTF-8"))

(defn fetch!
  "The download in `dir`, fetched when missing, and checked."
  [{:keys [url sha256] :as d} dir]
  (let [f (fs/file dir (file-name url))]
    (when-not (fs/exists? f)
      (fs/create-dirs dir)
      (println "Downloading" url)
      ;; curl, which follows the system's proxy settings; Wikimedia asks
      ;; for a User-Agent that says who is asking, and answers bursts with
      ;; 429, which --retry waits out
      (p/shell "curl" "-fsSL" "--retry" "6" "--retry-delay" "20"
               "-A" "clogem-support-trailer (https://github.com/EchoJustus/clogem-support)"
               "-o" (str f) url))
    (let [got (trailer.footage/sha256 f)]
      (when-not (= sha256 got)
        (throw (ex-info (str (fs/file-name f) " isn't the pinned file: SHA-256 " got) {:download d}))))
    (str f)))

(defn cut-args
  "FFmpeg's arguments for one clip from `src`: the parts joined, then
  :crop, :scale (\"1920:1080\") and :fps where given. No sound: some sources
  have none, and the trailer lays its own music under everything."
  [src {:keys [parts crop scale fps out]} dir]
  (let [n     (count parts)
        trims (str/join ";" (for [[i [a b]] (map-indexed vector parts)]
                              (str "[0:v]trim=" a ":" b ",setpts=PTS-STARTPTS[v" i "]")))
        join  (str (apply str (for [i (range n)] (str "[v" i "]"))) "concat=n=" n ":v=1:a=0[vc]")
        looks (remove nil? [(when crop (str "crop=" crop))
                            (when scale (str "scale=" scale ":flags=lanczos"))
                            (when fps (str "fps=" fps))])
        video (str ";[vc]" (if (seq looks) (str/join "," looks) "null") "[v]")]
    ["ffmpeg" "-v" "error" "-y" "-i" (str src) "-filter_complex" (str trims ";" join video)
     "-map" "[v]" "-an" "-c:v" "libx264" "-crf" "16" "-preset" "slow" "-pix_fmt" "yuv420p"
     "-movflags" "+faststart" (str (fs/file dir out))]))

(defn -main [& args]
  (let [opts    (into {} (map (fn [[k v]] [(keyword (subs k 2)) v])) (partition 2 args))
        trailer (or (:trailer opts) "trailers/wmark-pro")
        out     (or (:out opts) "target/footage/clips")
        {:keys [downloads clips]} (edn/read-string (slurp (fs/file trailer "footage.edn")))
        files   (into {} (for [d downloads] [(:id d) (fetch! d "target/footage")]))]
    (fs/create-dirs out)
    (doseq [{:keys [from] :as clip} clips]
      (apply p/shell (cut-args (files from) clip out))
      (println "Cut" (str (fs/file out (:out clip)))))
    (doseq [d downloads] (println "Credit:" (:credit d)))))
