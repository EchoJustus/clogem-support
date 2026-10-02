;; Copyright 2026 EchoJustus. Part of clogem-support.
;; SPDX-License-Identifier: MIT
(ns trailer.check
  "Checks a trailer against the Microsoft Store's rules for trailers (\"App
  screenshots, images, and trailers\", Microsoft Learn): the requirements
  (MP4 or MOV, 1920x1080, under 2 GB; a 1920x1080 PNG thumbnail; WebVTT
  captions under 50 MB) and the MP4 recipe (H.264 High, progressive, 4:2:0,
  at most two consecutive B frames; AAC-LC stereo at 48 kHz; the moov atom
  at the front, no edit lists). Sixty seconds or less is a recommendation,
  so a longer trailer is a warning. Reads the files with ffprobe and its own
  look at the MP4's boxes."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]))

(defn probe [file]
  (-> (p/shell {:out :string :err :string} "ffprobe" "-v" "error" "-print_format" "json"
               "-show_format" "-show_streams" (str file))
      :out (json/parse-string true)))

(defn- boxes
  "The boxes in `in` between `from` and `to`: [[type offset size]]."
  [^java.io.RandomAccessFile in from to]
  (loop [pos from out []]
    (if (or (>= (+ pos 8) to) (> (count out) 256))
      out
      (let [_    (.seek in pos)
            size (bit-and (.readInt in) 0xFFFFFFFF)
            kind (let [b (byte-array 4)] (.readFully in b) (String. b "ISO-8859-1"))
            size (case size 1 (.readLong in) 0 (- to pos) size)]
        (if (< size 8) (conj out [kind pos 0]) (recur (+ pos size) (conj out [kind pos size])))))))

(defn top-boxes
  "The MP4's top-level box types, in order (ftyp, moov, mdat, ...)."
  [file]
  (with-open [in (java.io.RandomAccessFile. (str file) "r")]
    (mapv first (boxes in 0 (.length in)))))

(defn edit-lists?
  "Whether any track in the MP4 has an edit list (moov > trak > edts)."
  [file]
  (with-open [in (java.io.RandomAccessFile. (str file) "r")]
    (boolean
     (some (fn [[kind pos size]]
             (when (= kind "moov")
               (some (fn [[k p s]]
                       (when (= k "trak")
                         (some #(= "edts" (first %)) (boxes in (+ p 8) (+ p s)))))
                     (boxes in (+ pos 8) (+ pos size)))))
           (boxes in 0 (.length in))))))

(defn video-problems
  "{:errors [...] :warnings [...]} for the trailer `file`."
  [file]
  (let [{:keys [streams format]} (probe file)
        v     (first (filter #(= "video" (:codec_type %)) streams))
        a     (first (filter #(= "audio" (:codec_type %)) streams))
        size  (fs/size file)
        len   (parse-double (:duration format))
        boxes (top-boxes file)
        err   (fn [ok? msg] (when-not ok? msg))]
    {:errors
     (remove nil?
             [(err (str/includes? (:format_name format) "mp4") "the file isn't an MP4")
              (err (< size (* 2 1024 1024 1024)) "the file is 2 GB or more")
              (err v "there's no video")
              (err (and v (= 1920 (:width v)) (= 1080 (:height v))) "the video isn't 1920x1080")
              (err (and v (= "h264" (:codec_name v))) "the video isn't H.264")
              (err (and v (= "High" (:profile v))) "the video isn't H.264 High profile")
              (err (and v (= "yuv420p" (:pix_fmt v))) "the video isn't 4:2:0 (yuv420p)")
              (err (and v (contains? #{nil "progressive"} (:field_order v))) "the video isn't progressive")
              (err (and v (<= (or (:has_b_frames v) 0) 2)) "more than two consecutive B frames")
              (err a "there's no sound")
              (err (and a (= "aac" (:codec_name a)) (= "LC" (:profile a))) "the sound isn't AAC-LC")
              (err (and a (= "48000" (:sample_rate a))) "the sound isn't 48 kHz")
              (err (and a (= 2 (:channels a))) "the sound isn't stereo")
              (err (let [m (.indexOf ^java.util.List boxes "moov") d (.indexOf ^java.util.List boxes "mdat")]
                     (and (>= m 0) (or (neg? d) (< m d))))
                   "the moov atom isn't at the front (fast start)")
              (err (not (edit-lists? file)) "the file has edit lists")])
     :warnings
     (remove nil?
             [(when (> len 60.0) (format "it runs %.1f s; the Store recommends 60 s or less" len))
              (when (and a (not= "384000" (:bit_rate a))) (str "the sound is " (:bit_rate a) " bit/s; the Store's recipe says 384 kbps"))
              (when (< (parse-long (or (:bit_rate format) "0")) 40000000)
                (str "the video averages " (quot (parse-long (or (:bit_rate format) "0")) 1000)
                     " kbit/s; the Store's recipe says 50 Mbps (bb trailer --quality store)"))])}))

(defn thumbnail-problems [file]
  (let [{:keys [streams]} (probe file)
        v (first streams)]
    (remove nil?
            [(when-not (= "png" (:codec_name v)) "the thumbnail isn't a PNG")
             (when-not (and (= 1920 (:width v)) (= 1080 (:height v))) "the thumbnail isn't 1920x1080")])))

(defn captions-problems [file]
  (remove nil? [(when-not (str/ends-with? (str file) ".vtt") "the captions aren't a .vtt file")
                (when-not (< (fs/size file) (* 50 1024 1024)) "the captions file is 50 MB or more")]))
