;; Copyright 2026 EchoJustus. Part of clogem-support.
;; SPDX-License-Identifier: MIT
(ns trailer.music
  "A soundtrack of our own, so a trailer carries no one else's music: a calm
  pad in D major (vi-IV-I-V, a chord every two bars at 96 BPM), a soft bass,
  a plucked arpeggio and a quiet pulse, ending on the tonic. It is written
  as data here and synthesized by FFmpeg (aevalsrc, one source per chord and
  voice), then given room (aecho), trimmed (high and low pass) and brought
  to -16 LUFS, the level the Microsoft Store recommends for trailers.

    bb trailer music --duration 57.2 --out target/trailer/music.wav"
  (:require [clojure.string :as str]))

(def ^:private chord-s 5.0)                ; two bars at 96 BPM
(def ^:private eighth-s 0.3125)
(def ^:private pulse-s 1.25)               ; beats one and three

(def chords
  "Each chord: pad voices (close voicing, smooth voice leading), a bass note
  and four arpeggio notes, in Hz."
  {:bm {:pad [146.83 185.00 246.94] :bass 61.74 :arp [493.88 587.33 739.99 587.33]}
   :g  {:pad [146.83 196.00 246.94] :bass 49.00 :arp [392.00 493.88 587.33 493.88]}
   :d  {:pad [146.83 185.00 220.00] :bass 73.42 :arp [587.33 739.99 880.00 739.99]}
   :a  {:pad [138.59 164.81 220.00] :bass 55.00 :arp [554.37 659.26 880.00 659.26]}})

(def ^:private progression [:bm :g :d :a])

(defn plan
  "The chords of a soundtrack `duration` seconds long: [start chord length]
  each, the last one the tonic (D), held to the end."
  [duration]
  (let [n     (max 2 (long (Math/floor (/ duration chord-s))))
        slots (vec (for [i (range n)] [(* i chord-s) (progression (mod i (count progression)))]))
        [start _] (peek slots)]
    (conj (pop slots) [start :d (- duration start)])))

(defn- fmt [x] (format "%.4f" (double x)))

(defn- sum [terms] (str "(" (str/join "+" terms) ")"))

(defn- source
  "One aevalsrc: stereo expressions `l` and `r`, `d` seconds, delayed to
  `at` seconds."
  [l r d at]
  (let [ms (long (Math/round (* 1000.0 at)))]
    (str "aevalsrc=exprs='" l "|" r "':s=48000:d=" (fmt d) ",adelay=" ms "|" ms)))

(defn- pad [freqs length]
  ;; a slow swell, held, then a release that overlaps the next chord
  (let [env   (str "min(t/1.2,1)*if(lt(t," (fmt length) "),1,max(0,1-(t-" (fmt length) ")/1.8))")
        voice (fn [detune]
                (sum (for [f freqs :let [f (* f detune)]]
                       (str "0.07*(sin(2*PI*" (fmt f) "*t)+0.25*sin(4*PI*" (fmt f) "*t)+0.08*sin(6*PI*" (fmt f) "*t))"))))]
    [(str env "*" (voice 1.0)) (str env "*" (voice 1.0021))]))

(defn- bass [f length]
  (let [env (str "min(t/0.08,1)*if(lt(t," (fmt length) "),1,max(0,1-(t-" (fmt length) ")/0.8))")
        sig (str "0.12*sin(2*PI*" (fmt f) "*t)+0.05*sin(4*PI*" (fmt f) "*t)")]
    [(str env "*(" sig ")") (str env "*(" sig ")")]))

(defn- arp [[f0 f1 f2 f3] gain]
  ;; eighth notes over the chord, each plucked: a fast attack, an exponential fall
  (let [k   (str "mod(floor(t/" eighth-s ")," 4 ")")
        f   (str "if(eq(" k ",0)," (fmt f0) ",if(eq(" k ",1)," (fmt f1) ",if(eq(" k ",2)," (fmt f2) "," (fmt f3) ")))")
        u   (str "(t-floor(t/" eighth-s ")*" eighth-s ")")
        env (str "min(" u "/0.008,1)*exp(-5*" u ")")
        sig (str (fmt gain) "*" env "*(sin(2*PI*" f "*t)+0.3*exp(-3*" u ")*sin(4*PI*" f "*t))")]
    ;; the right channel a touch later, for width
    [sig (str "if(lt(t,0.012),0," (str/replace sig "*t)" "*(t-0.012))") ")")]))

(defn- pulse [length]
  (let [u   (str "mod(t," pulse-s ")")
        sig (str "0.22*exp(-9*" u ")*sin(2*PI*(48*" u "+2*(1-exp(-30*" u "))))")
        env (str "if(lt(t," (fmt length) "),1,0)")]
    [(str env "*" sig) (str env "*" sig)]))

(defn sources
  "Every aevalsrc of the soundtrack, as filter chains."
  [duration]
  (let [slots   (plan duration)
        arp-in  8.0
        arp-out (- duration 5.5)]
    (concat
     (for [[start c length] slots
           :let [length (or length chord-s)
                 [l r] (pad (:pad (chords c)) length)]]
       (source l r (+ length 1.9) start))
     (for [[start c length] slots
           :let [length (or length chord-s)]
           :when (>= start (- arp-in chord-s 0.01))
           :let [[l r] (bass (:bass (chords c)) length)]]
       (source l r (+ length 0.9) start))
     (for [[start c length] slots
           :let [length (or length chord-s)
                 end    (min (+ start length) arp-out)
                 from   (max start arp-in)]
           :when (< from end)
           ;; the arpeggio grows from a whisper as the trailer gets going
           :let [gain (if (< from 14.0) 0.035 0.05)
                 [l r] (arp (:arp (chords c)) gain)]]
       (source l r (- end from) from))
     (let [from 15.0 to (- duration 6.0)]
       (when (< from to)
         (let [[l r] (pulse (- to from))]
           [(source l r (- to from) from)]))))))

(defn graph
  "The whole filtergraph: the sources mixed, given room, filtered, faded and
  brought to -16 LUFS, `duration` seconds long."
  [duration]
  (let [srcs (vec (sources duration))
        n    (count srcs)]
    (str (str/join ";" (map-indexed (fn [i s] (str s "[s" i "]")) srcs))
         ";" (apply str (map #(str "[s" % "]") (range n)))
         "amix=inputs=" n ":normalize=0:duration=longest,"
         "aecho=0.8:0.55:95|170:0.32|0.22,highpass=f=35,lowpass=f=9000,"
         "atrim=0:" (fmt duration) ",afade=t=in:d=1.0,afade=t=out:st=" (fmt (- duration 2.5)) ":d=2.5,"
         "loudnorm=I=-16:TP=-1.5:LRA=11,aresample=48000[out]")))

(defn render!
  "Writes the soundtrack to `out` (WAV, 48 kHz stereo)."
  [duration out]
  (let [args ["ffmpeg" "-v" "error" "-y" "-filter_complex" (graph duration) "-map" "[out]"
              "-c:a" "pcm_s16le" "-ar" "48000" "-ac" "2" (str out)]
        p    (.start (doto (ProcessBuilder. ^java.util.List args) (.inheritIO)))]
    (when-not (zero? (.waitFor p)) (throw (ex-info "FFmpeg couldn't make the soundtrack" {:out out})))
    out))
