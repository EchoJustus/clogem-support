;; Copyright 2026 EchoJustus. Part of clogem-support.
;; SPDX-License-Identifier: MIT
(ns trailer-test
  "The trailer toolchain (scripts/trailer) and the Wmark Pro trailer it made
  (trailers/wmark-pro): the timeline's arithmetic, the words in every
  language, the committed captions against the words, and the committed
  video, thumbnail and captions against the Microsoft Store's rules."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [trailer.captions :as captions]
            [trailer.check :as check]
            [trailer.edit :as edit]
            [trailer.footage :as footage]
            [trailer.main :as main]
            [trailer.music :as music]))

(def ^:private dir "trailers/wmark-pro")
(def ^:private made (main/plan dir (str dir "/sources")))
(def ^:private spec (:spec made))

(defn- on-path? [exe]
  (some #(fs/executable? (fs/file % exe)) (str/split (or (System/getenv "PATH") "") #":")))

(def ^:private ffmpeg? (and (on-path? "ffmpeg") (on-path? "ffprobe")))

(deftest the-timeline-adds-up
  (testing "times name the take's marks"
    (is (= 2.5 (edit/at {:a 2.0} [:a 0.5])))
    (is (= 3.0 (edit/at {} 3)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No mark :b" (edit/at {:a 1.0} [:b 0]))))
  (testing "parts: cut, sped up, held"
    (is (= 2.0 (edit/part-length {} {:in 1 :out 5 :speed 2})))
    (is (= 1.5 (edit/part-length {} {:hold {:at 3 :for 1.5}}))))
  (testing "segments overlap by the crossfade"
    (let [{:keys [segments length]} (edit/timeline {} {:crossfade 0.5
                                                       :segments [{:id :a :parts [{:in 0 :out 4}]}
                                                                  {:id :b :title {:length 3}}
                                                                  {:id :c :parts [{:in 0 :out 2}]}]})]
      (is (= [0.0 3.5 6.0] (map :start segments)))
      (is (= 8.0 length))))
  (testing "cards land inside their segments; negative times count from the end"
    (let [edl {:crossfade 0.4 :segments [{:id :a :parts [{:in 0 :out 5}]}
                                         {:id :b :parts [{:in 0 :out 4}] :cards [{:text :x :from 0.5 :until -0.5}]}]}]
      (is (= [{:text :x :from 0.5 :until -0.5 :start 5.1 :end 8.1}]
             (map #(update (update % :start (fn [t] (/ (Math/round (* 10 t)) 10.0))) :end (fn [t] (/ (Math/round (* 10 t)) 10.0)))
                  (edit/cards {} edl)))))))

(deftest the-wmark-pro-cut
  (let [{:keys [timeline cards edl]} made]
    (testing "a minute or less, as the Store recommends"
      (is (< 45.0 (:length timeline) 60.0)))
    (testing "every card shows inside its own segment, for at least two seconds"
      (doseq [{:keys [text start end]} cards]
        (is (<= 2.0 (- end start)) (str text))))
    (testing "every time in the edit list names a mark the take has"
      (let [marks (set (keys (:marks made)))
            named (for [{:keys [parts]} (:segments edl) part parts t [(:in part) (:out part)] :when (vector? t)] (first t))]
        (is (seq named))
        (is (set/subset? (set named) marks))))
    (testing "the take's marks are the recording's"
      (let [steps (:steps (edn/read-string (slurp (str dir "/recording.edn"))))]
        (is (= (set (keys (:marks made))) (set (for [[op m] steps :when (= op :mark)] m))))
        (is (every? #{:mark :wait :move :click :double :shift-click :drag :scroll :type :key :keys :shot} (map first steps)))))))

(deftest the-words-in-every-language
  (let [langs (:languages spec)
        used  (set (concat (mapcat (juxt :text :sub) (:cards made))
                           (map :word (:sounds spec))
                           (mapcat :words (:end-cards spec))))]
    (testing "the eighteen languages of the app's Store listing, by their neutral tags"
      (is (= ["en" "fr" "zh-Hans" "zh-Hant" "ru" "ja" "ko" "ar" "ta" "sv" "ms" "fi" "hi" "es" "pt" "de" "id" "it"]
             langs)))
    (testing "every captioned word is there in each of them, and no line passes 50 characters"
      (doseq [id (remove nil? used) lang langs
              :let [w (get-in spec [:words id lang])]]
        (is (not (str/blank? w)) (str id " " lang))
        (is (every? #(<= (count %) 50) (str/split-lines (or w ""))) (str id " " lang))))
    (testing "honest words: no \"subliminal\", and the end card says what watermarks can't do"
      (doseq [[_ by-lang] (:words spec) [_ w] by-lang]
        (is (not (re-find #"(?i)subliminal" w))))
      (is (some #{:honest} (mapcat :words (:end-cards spec)))))))

(deftest the-captions
  (let [cues (captions/cues spec (:timeline made) (:cards made))
        len  (:length (:timeline made))]
    (testing "the committed files are what the words and the edit list make, in every language"
      (doseq [lang (:languages spec)
              :let [f (fs/file dir "captions" (str "wmark-pro-trailer." lang ".vtt"))]]
        (is (fs/exists? f) (str f))
        (when (fs/exists? f)
          (is (= (captions/vtt spec lang cues) (slurp f)) (str f " is out of date: bb trailer --only captions"))
          (is (empty? (captions/problems (slurp f) len)) (str f)))))
    (testing "cues follow each other, inside the trailer"
      (is (every? (fn [[a b]] (<= (:end a) (:start b))) (partition 2 1 cues)))
      (is (<= (:end (peek cues)) len)))
    (testing "a cue moves to the bottom while a card shows in the upper band"
      (let [upper (filter #(= :upper (:place %)) (:cards made))]
        (doseq [{:keys [start end top?]} cues
                :when (some #(and (< (:start %) end) (< start (:end %))) upper)]
          (is (false? top?)))))
    (testing "words are text, never markup"
      (is (str/includes? (captions/vtt {:title "t" :notice "n" :words {:w {"x" "<b> & </b>"}}} "x"
                                       [{:start 0 :end 1 :words [:w]}])
                         "&lt;b&gt; &amp; &lt;/b&gt;")))
    (testing "the checker reads WebVTT back"
      (is (= [{:start 1.5 :end 3.25 :text "a\nb"}]
             (captions/parse "WEBVTT\n\nNOTE\nx\n\n1\n00:00:01.500 --> 00:00:03.250 line:10%\na\nb\n")))
      (is (seq (captions/problems "WEBVTT\n\n1\n00:00:02.000 --> 00:00:01.000\nx\n" 5.0))))))

(deftest the-soundtrack
  (let [plan (music/plan 53.9)]
    (testing "a chord every two bars, the last on the tonic, held to the end"
      (is (= (range 0.0 50.0 5.0) (map first plan)))
      (is (= :d (second (peek plan))))
      (is (< (Math/abs (- 53.9 (+ (first (peek plan)) (nth (peek plan) 2)))) 1e-9)))
    (testing "brought to the Store's -16 LUFS"
      (is (str/includes? (music/graph 20.0) "loudnorm=I=-16")))))

(deftest the-footage-recipe
  (let [{:keys [downloads clips]} (edn/read-string (slurp (str dir "/footage.edn")))]
    (testing "each source pinned, from Wikimedia Commons, with its page and credit"
      (doseq [{:keys [url page sha256 credit]} downloads]
        (is (str/starts-with? url "https://upload.wikimedia.org/"))
        (is (str/starts-with? page "https://commons.wikimedia.org/wiki/File:"))
        (is (re-matches #"[0-9a-f]{64}" sha256))
        (is (str/includes? credit "public domain"))))
    (testing "the clips are cut from those sources: silent, cropped, scaled and retimed where asked"
      (is (every? (set (map :id downloads)) (map :from clips)))
      (let [args (footage/cut-args "in.mp4" {:out "o.mp4" :parts [[1 2] [3 4]] :crop "1920:818:0:130"
                                             :scale "1920:1080" :fps 30} "d")]
        (is (str/includes? (nth args 7) "concat=n=2:v=1:a=0"))
        (is (str/includes? (nth args 7) "crop=1920:818:0:130,scale=1920:1080:flags=lanczos,fps=30"))
        (is (some #{"-an"} args))))
    (testing "a download keeps its URL's file name, decoded"
      (is (= "A_(1).webm" (footage/file-name "https://example.org/a/A_%281%29.webm"))))))

(deftest the-committed-trailer-meets-the-store
  (if-not ffmpeg?
    (println "Skipping the trailer's Store check: no ffmpeg/ffprobe on PATH")
    (let [video (str dir "/wmark-pro-trailer.mp4")
          {:keys [errors]} (check/video-problems video)]
      (is (empty? errors) (str/join "; " errors))
      (is (empty? (check/thumbnail-problems (str dir "/wmark-pro-trailer-thumbnail.png"))))
      (testing "as long as its edit list says"
        (let [len (parse-double (get-in (check/probe video) [:format :duration]))]
          (is (< (Math/abs (- len (:length (:timeline made)))) 0.1))))
      (testing "small enough for git"
        (is (< (fs/size video) (* 50 1000 1000)))))))

(deftest the-check-catches-what-the-store-refuses
  (if-not ffmpeg?
    (println "Skipping the checker's own test: no ffmpeg on PATH")
    (let [bad (str (fs/file (fs/create-temp-dir) "bad.mp4"))]
      (p/shell {:out :string :err :string} "ffmpeg" "-v" "error" "-y"
               "-f" "lavfi" "-i" "testsrc2=s=1280x720:r=25:d=1" "-f" "lavfi" "-i" "sine=f=440:r=44100:d=1"
               "-c:v" "libx264" "-profile:v" "main" "-c:a" "aac" "-ac" "1" bad)
      (let [errors (set (:errors (check/video-problems bad)))]
        (is (contains? errors "the video isn't 1920x1080"))
        (is (contains? errors "the video isn't H.264 High profile"))
        (is (contains? errors "the sound isn't 48 kHz"))
        (is (contains? errors "the sound isn't stereo"))
        (is (contains? errors "the moov atom isn't at the front (fast start)"))))))
