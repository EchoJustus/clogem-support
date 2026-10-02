;; Copyright 2026 EchoJustus. Part of clogem-support.
;; SPDX-License-Identifier: MIT
(ns trailer.captions
  "Closed captions for a trailer, one WebVTT file per language, from the
  same words the trailer shows (trailer.edit draws the English ones):

    - every caption card on screen becomes a cue, in that language, while
      the card shows; for viewers of other languages it is the card's text
      in their own;
    - sounds are cues too, in brackets, as captions for people who don't
      hear them ([Calm electronic music]);
    - the end card's words get a cue of their own.

  A cue never overlaps the one before it. It sits near the top of the
  picture (line:10%), out of the way of the cards in the lower band, and at
  the bottom while a card shows in the upper band. Partner Center takes a
  .vtt of less than 50 MB for each trailer and listing language."
  (:require [clojure.string :as str]))

(defn- seg-time
  "Absolute seconds of `t` within segment `id` (negative counts from its end)."
  [timeline id t]
  (let [{:keys [start length]} (or (some #(when (= id (:id %)) %) (:segments timeline))
                                   (throw (ex-info (str "No segment " id) {:segment id})))]
    (+ start (if (neg? t) (+ length t) t))))

(defn cues
  "The cues, in order, each {:start :end :words [ids] :top?}: from the
  cards (trailer.edit/cards), the sounds and the end card in `spec`."
  [spec timeline cards]
  (let [upper  (filter #(= :upper (:place %)) cards)
        top?   (fn [s e] (not-any? #(and (< (:start %) e) (< s (:end %))) upper))
        raw    (concat
                (for [{:keys [text sub start end]} cards]
                  {:start start :end end :words (vec (remove nil? [text sub]))})
                (for [{:keys [word segment from until]} (:sounds spec)]
                  {:start (seg-time timeline segment from) :end (seg-time timeline segment until) :words [word]})
                (for [{:keys [segment from until words]} (:end-cards spec)]
                  {:start (seg-time timeline segment from) :end (seg-time timeline segment until) :words words}))
        sorted (sort-by :start raw)]
    (->> (reduce (fn [out {:keys [start end] :as cue}]
                   (let [start (max start (if-let [prev (peek out)] (:end prev) 0.0))]
                     (if (< (+ start 0.5) end) (conj out (assoc cue :start start)) out)))
                 [] sorted)
         (mapv #(assoc % :top? (top? (:start %) (:end %)))))))

(defn timestamp
  "WebVTT's hh:mm:ss.ttt."
  [seconds]
  (let [ms (Math/round (* 1000.0 (double seconds)))]
    (format "%02d:%02d:%02d.%03d" (quot ms 3600000) (mod (quot ms 60000) 60) (mod (quot ms 1000) 60) (mod ms 1000))))

(defn- escape [s] (-> s (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;")))

(defn vtt
  "The WebVTT text for `lang`."
  [spec lang cues]
  (let [word (fn [id] (or (get-in spec [:words id lang])
                          (throw (ex-info (str "No " lang " words for " id) {:word id :lang lang}))))]
    (str "WEBVTT\n\n"
         "NOTE\n" (:title spec) ", closed captions (" lang ").\n" (:notice spec) "\n\n"
         (str/join "\n"
           (map-indexed
            (fn [i {:keys [start end words top?]}]
              (str (inc i) "\n" (timestamp start) " --> " (timestamp end) (when top? " line:10%") "\n"
                   (str/join "\n" (map (comp escape word) words)) "\n"))
            cues)))))

(defn parse
  "A WebVTT file's cues, as [{:start :end :text}], for checking."
  [text]
  (let [secs (fn [s] (let [[_ h m sec ms] (re-matches #"(\d+):(\d\d):(\d\d)\.(\d{3})" s)]
                       (+ (* 3600 (parse-long h)) (* 60 (parse-long m)) (parse-long sec) (/ (parse-long ms) 1000.0))))]
    (for [block (rest (str/split (str/replace text "\r\n" "\n") #"\n\n+"))
          :let [lines (str/split-lines block)
                [timing & body] (drop-while #(not (str/includes? % "-->")) lines)]
          :when timing
          :let [[_ a b] (re-find #"^(\S+) --> (\S+)" timing)]]
      {:start (secs a) :end (secs b) :text (str/join "\n" body)})))

(defn problems
  "What is wrong with a WebVTT file for a trailer `length` seconds long, in
  words: its header, its cues' order and bounds, and line lengths."
  [text length]
  (let [cs (vec (parse text))]
    (concat
     (when-not (str/starts-with? text "WEBVTT") ["it doesn't start with WEBVTT"])
     (when (empty? cs) ["it has no cues"])
     (for [[i {:keys [start end text]}] (map-indexed vector cs)
           problem [(when-not (< start end) "ends before it starts")
                    (when (> end (+ length 0.001)) "ends after the trailer")
                    (when (and (pos? i) (< start (:end (cs (dec i))))) "overlaps the cue before it")
                    (when (str/blank? text) "is empty")
                    (when-let [long-line (first (filter #(> (count %) 50) (str/split-lines text)))]
                      (str "has a line longer than 50 characters: " long-line))]
           :when problem]
       (str "cue " (inc i) " " problem)))))
