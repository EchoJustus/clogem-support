;; Copyright 2026 EchoJustus. Part of clogem-support.
;; SPDX-License-Identifier: MIT
(ns trailer.edit
  "Cuts a trailer from an edit list (EDN): segments of footage, each one or
  more parts cut from a source, sped up, zoomed or held on a frame, with
  caption cards over them; title cards drawn here; crossfades between
  segments; a soundtrack (trailer.music) under it all. Then one encode to
  the Microsoft Store's MP4 specification (trailer.check checks it).

  The words on the cards come from the captions file, in English
  (trailer.captions), so the on-screen text and the closed captions can't
  drift apart. Times may name the take's marks (trailer.record):
  [:scrub 0.5] is half a second after the mark :scrub.

  Every segment is rendered to its own file first, lossless enough to edit
  again, then joined; FFmpeg draws all of it, text included, through
  textfile= with expansion off, so no word is parsed as a filter."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Time

(defn at
  "Seconds for `t`: a number, or [mark offset] in the take's marks."
  [marks t]
  (if (vector? t)
    (let [[mark offset] t]
      (+ (or (get marks mark) (throw (ex-info (str "No mark " mark " in the take") {:mark mark})))
         (or offset 0)))
    (double t)))

(defn part-length
  "How long a part runs in the trailer."
  [marks {:keys [in out speed hold]}]
  (if hold
    (double (:for hold))
    (/ (- (at marks out) (at marks in)) (double (or speed 1)))))

(defn segment-length [marks {:keys [parts title]}]
  (if title (double (:length title)) (reduce + (map #(part-length marks %) parts))))

(defn timeline
  "Each segment's start and end in the finished trailer, given the
  crossfade between segments: [{:id :start :end :length}], and the total."
  [marks {:keys [segments crossfade]}]
  (let [fade (double (or crossfade 0))]
    (loop [[s & more] segments, start 0.0, out []]
      (if-not s
        {:segments out :length (if (seq out) (:end (peek out)) 0.0)}
        (let [len (segment-length marks s)]
          (recur more (- (+ start len) fade)
                 (conj out {:id (:id s) :start start :end (+ start len) :length len})))))))

(defn cards
  "Every caption card with its absolute time in the trailer:
  [{:text id :sub id :start :end :place}]."
  [marks edl]
  (let [{:keys [segments]} (timeline marks edl)
        by-id (into {} (map (juxt :id identity)) segments)]
    (for [{:keys [id cards]} (:segments edl)
          {:keys [from until] :as card} cards
          :let [{:keys [start length]} (by-id id)
                until (if (neg? until) (+ length until) until)]]
      (assoc card :start (+ start from) :end (+ start until)))))

;; ---------------------------------------------------------------------------
;; Drawing

(defn- ffmpeg! [args]
  (let [r (apply p/shell {:out :string :err :string :continue true} args)]
    (when-not (zero? (:exit r))
      (throw (ex-info (str "FFmpeg failed: " (str/join " " (take 3 (drop 1 args))) "\n" (:err r)) {:args args})))
    r))

(defn- escape-path
  "A file path inside a filter argument: quoted, with : and ' escaped."
  [path]
  (str "'" (-> (str path) (str/replace "\\" "/") (str/replace "'" "'\\''") (str/replace ":" "\\:")) "'"))

(def ^:private W 1920)
(def ^:private H 1080)

(defn- fit
  "Filters that bring a part to 1920x1080: :letterbox pads a wide picture,
  :zoom [x y w h] crops a region and scales it up, else it is scaled to
  fill."
  [{:keys [zoom fit]}]
  (cond
    zoom (let [[x y w h] zoom] (str "crop=" w ":" h ":" x ":" y ",scale=" W ":" H ":flags=lanczos"))
    (= fit :letterbox) (str "scale=" W ":-2:flags=lanczos,pad=" W ":" H ":(ow-iw)/2:(oh-ih)/2:black")
    :else (str "scale=" W ":" H ":flags=lanczos")))

(defn- part-filter
  "The filter chain for one part of a segment, from input `idx`."
  [marks fps idx {:keys [in out speed hold] :as part} seg]
  (let [look (fit (merge seg part))]
    (if hold
      (let [t (at marks (:at hold))]
        (str "[" idx ":v]trim=start=" t ",setpts=PTS-STARTPTS,trim=end_frame=1,"
             "tpad=stop_mode=clone:stop_duration=" (:for hold) ",fps=" fps ","
             "trim=duration=" (:for hold) "," look ",setsar=1,format=yuv420p"))
      (str "[" idx ":v]trim=start=" (at marks in) ":end=" (at marks out) ",setpts=(PTS-STARTPTS)/" (double (or speed 1)) ","
           "fps=" fps "," look ",setsar=1,format=yuv420p"))))

(defn- card-layer
  "A transparent layer with one caption card: a soft dark band across the
  frame and the words on it, faded in and out. `words` maps a word id to
  its text file."
  [{:keys [fonts]} words length {:keys [text sub from until place] :or {place :lower}}]
  (let [until  (if (neg? until) (+ length until) until)
        mid    (if (= place :upper) 230 (- H 250))
        band   (str "geq=r=14:g=10:b=31:a='170*clip(1-abs(Y-" mid ")/150,0,1)*min(1,(1-abs(Y-" mid ")/150)*2.2)'")
        title  (str "drawtext=fontfile=" (escape-path (:semibold fonts)) ":textfile=" (escape-path (words text))
                    ":expansion=none:fontsize=54:fontcolor=white:x=150:y=" (- mid (if sub 52 28)))
        subt   (when sub
                 (str ",drawtext=fontfile=" (escape-path (:regular fonts)) ":textfile=" (escape-path (words sub))
                      ":expansion=none:fontsize=34:fontcolor=0xE6DEFF:x=152:y=" (+ mid 18)))]
    (str "color=c=black@0:s=" W "x" H ":r=30:d=" length ",format=rgba," band "," title subt ","
         "fade=t=in:st=" from ":d=0.35:alpha=1,fade=t=out:st=" (- until 0.35) ":d=0.35:alpha=1")))

(defn- title-filter
  "A title card: a frame of a source, blurred and darkened under a violet
  wash, the icon, and the words."
  [marks {:keys [fonts]} words icon-idx bg-idx {:keys [title]}]
  (let [{:keys [length background lines end?]} title
        bg   (str "[" bg-idx ":v]trim=start=" (at marks (:at background)) ",setpts=PTS-STARTPTS,trim=end_frame=1,"
                  "scale=-2:" H ",crop=" W ":" H ",gblur=sigma=38,eq=brightness=-0.18:saturation=0.75,"
                  "tpad=stop_mode=clone:stop_duration=" length ",fps=30,trim=duration=" length ",setsar=1[bg];"
                  "color=c=0x1B1440@0.62:s=" W "x" H ":r=30:d=" length ",format=rgba[wash];"
                  "[bg][wash]overlay=format=auto[bgw];")
        size (if end? 200 260)
        top  (if end? 150 250)
        icon (str "[" icon-idx ":v]scale=" size ":" size ":flags=lanczos,format=rgba[ic];"
                  "[bgw][ic]overlay=x=(W-w)/2:y=" top "[base]")
        text (str/join ","
                (for [{:keys [word font size color y]} lines]
                  (str "drawtext=fontfile=" (escape-path (get fonts font)) ":textfile=" (escape-path (words word))
                       ":expansion=none:fontsize=" size ":fontcolor=" color ":x=(w-text_w)/2:y=" y)))]
    (str bg icon ";[base]" text ",format=yuv420p")))

(defn render-segment!
  "Renders segment `seg` to `out` (1920x1080, 30 fps, no sound)."
  [marks edl sources words seg out]
  (let [{:keys [parts title cards id]} seg
        length (segment-length marks seg)
        icon   (:icon sources)
        inputs (if title
                 [icon (get sources (get-in title [:background :from]))]
                 (mapv #(or (get sources (:from %)) (throw (ex-info (str "No source " (:from %)) {:part %}))) parts))
        base   (if title
                 (str (title-filter marks edl words 0 1 seg) "[v0]")
                 (str (str/join ";" (map-indexed (fn [i part] (str (part-filter marks 30 i part seg) "[p" i "]")) parts))
                      ";" (apply str (map #(str "[p" % "]") (range (count parts))))
                      "concat=n=" (count parts) ":v=1:a=0[v0]"))
        layered (reduce (fn [[graph n] card]
                          [(str graph ";" (card-layer edl words length card) "[c" n "];"
                                "[v" n "][c" n "]overlay=format=auto,format=yuv420p[v" (inc n) "]")
                           (inc n)])
                        [base 0] cards)
        [graph n] layered
        args (concat ["ffmpeg" "-v" "error" "-y"]
                     (mapcat (fn [src] ["-i" (str src)]) inputs)
                     ["-filter_complex" graph "-map" (str "[v" n "]") "-t" (str length)
                      "-c:v" "libx264" "-preset" "medium" "-crf" "12" "-pix_fmt" "yuv420p" "-r" "30" "-an" (str out)])]
    (println (format "  %-8s %5.2f s -> %s" (name id) length (fs/file-name out)))
    (ffmpeg! args)
    out))

;; ---------------------------------------------------------------------------
;; The whole trailer

(def encodings
  "The Microsoft Store's MP4 recipe (\"App screenshots, images, and
  trailers\", Microsoft Learn): H.264 High, progressive, two consecutive B
  frames, closed GOPs of half the frame rate, CABAC, 4:2:0; AAC-LC stereo,
  48 kHz, 384 kbps; no edit lists, the moov atom first. :store is its
  50 Mbps; :repo the same recipe at a quality that keeps the file small
  enough for a git repository."
  {:store ["-b:v" "50M" "-maxrate" "50M" "-bufsize" "100M"]
   :repo  ["-crf" "17" "-maxrate" "14M" "-bufsize" "28M"]})

(defn encode!
  "Joins the segment files with crossfades, lays the soundtrack under them
  and encodes `out`."
  [segment-files lengths fade music out quality]
  (let [n      (count segment-files)
        offs   (reductions + (map #(- % fade) lengths))
        chain  (if (= 1 n)
                 "[0:v]format=yuv420p[v]"
                 (str/join ";"
                   (for [i (range 1 n)]
                     (str (if (= i 1) "[0:v]" (str "[x" (dec i) "]")) "[" i ":v]"
                          "xfade=transition=fade:duration=" fade ":offset=" (format "%.4f" (double (nth offs (dec i))))
                          (if (= i (dec n)) ",format=yuv420p[v]" (str "[x" i "]"))))))
        total  (- (reduce + lengths) (* fade (dec n)))
        args   (concat ["ffmpeg" "-v" "error" "-y"]
                       (mapcat (fn [f] ["-i" (str f)]) segment-files)
                       ["-i" (str music)
                        "-filter_complex" (str chain ";[" n ":a]atrim=0:" total ",asetpts=PTS-STARTPTS[a]")
                        "-map" "[v]" "-map" "[a]" "-t" (format "%.3f" total)
                        "-c:v" "libx264" "-profile:v" "high" "-level" "4.2" "-preset" "slow"
                        "-pix_fmt" "yuv420p" "-r" "30"
                        "-bf" "2" "-b_strategy" "0" "-g" "15" "-keyint_min" "15" "-sc_threshold" "0"
                        "-flags" "+cgop" "-coder" "cabac"]
                       (get encodings quality)
                       ["-c:a" "aac" "-profile:a" "aac_low" "-b:a" "384k" "-ar" "48000" "-ac" "2"
                        "-map_metadata" "-1" "-movflags" "+faststart" "-use_editlist" "0"
                        (str out)])]
    (ffmpeg! args)
    {:out out :length total}))

(defn thumbnail!
  "A still of the trailer at `t` seconds, as the Store's 1920x1080 PNG."
  [video t out]
  (ffmpeg! ["ffmpeg" "-v" "error" "-y" "-ss" (str t) "-i" (str video) "-frames:v" "1"
         "-vf" "scale=1920:1080:flags=lanczos,format=rgb24" (str out)])
  out)
