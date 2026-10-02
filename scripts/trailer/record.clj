;; Copyright 2026 EchoJustus. Part of clogem-support.
;; SPDX-License-Identifier: MIT
(ns trailer.record
  "Records an app on an X display while a script drives it, the way a person
  would: the pointer glides, clicks land, words are typed a key at a time.

    clojure -M:record --display :99 --shots trailers/wmark-pro/recording.edn \\
                      --out target/trailer/take.mkv

  The app is already running on the display; this finds its window by name,
  sizes it to the screen, then starts FFmpeg's x11grab and plays the shot
  list. Beside the take it writes the marks (take.marks.edn): when each
  `[:mark name]` was reached, in seconds from the take's first frame. The
  edit (trailer.edit) cuts on those marks.

  The shot list is EDN: {:window \"name regex\" :size [w h] :fps 30
  :steps [...]}, each step one of
    [:mark name]                  a named point in time, for the edit
    [:wait ms]
    [:move x y]  [:move x y ms]   glide the pointer there
    [:click x y] [:double x y] [:shift-click x y]
    [:drag x1 y1 x2 y2 ms]        press, glide, release
    [:scroll x y :up|:down n]
    [:type \"text\"]               a key at a time
    [:key \"Return\"]  [:keys \"Control_L\" \"l\"]
    [:shot name]                  a screenshot beside the take (for checking)"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [trailer.x11 :as x]))

(set! *warn-on-reflection* true)

(def ^:private pointer (atom [960 540]))

(defn- ease ^double [^double t] (if (< t 0.5) (* 4 t t t) (- 1 (/ (Math/pow (+ (* -2 t) 2) 3) 2))))

(defn glide!
  "Moves the pointer to x,y over `ms`, easing in and out, at about 120 steps
  a second."
  [d x y ms]
  (let [[x0 y0] @pointer
        steps   (max 1 (long (/ ms 8)))]
    (doseq [i (range 1 (inc steps))]
      (let [t (ease (/ (double i) steps))]
        (x/move! d (Math/round (+ x0 (* t (- x x0)))) (Math/round (+ y0 (* t (- y y0)))))
        (Thread/sleep 8)))
    (reset! pointer [x y])))

(defn- distance-ms [x y]
  (let [[x0 y0] @pointer d (Math/hypot (- x x0) (- y y0))]
    (long (min 900 (max 250 (* 0.55 d))))))

(defn- press! [d] (x/button! d 1 true) (Thread/sleep 70) (x/button! d 1 false))

(defn- tap! [d k] (x/key! d k true) (Thread/sleep 25) (x/key! d k false))

(defn- type! [d text]
  (doseq [c text]
    (let [[k shift?] (x/char-key c)]
      (when shift? (x/key! d "Shift_L" true))
      (tap! d k)
      (when shift? (x/key! d "Shift_L" false))
      (Thread/sleep (long (+ 45 (rand-int 50)))))))

(defn- screenshot! [display size ^String file]
  (let [[w h] size
        ^java.util.List cmd ["ffmpeg" "-v" "error" "-y" "-f" "x11grab" "-video_size" (str w "x" h)
                             "-i" display "-frames:v" "1" file]
        p (.start (ProcessBuilder. cmd))]
    (.waitFor p)))

(defn play!
  "Plays `steps` on display `d`; `mark!` is called with each mark's name."
  [d display size steps mark! shot-file]
  (doseq [[op & args :as step] steps]
    (case op
      :mark        (mark! (first args))
      :wait        (Thread/sleep (long (first args)))
      :move        (let [[x y ms] args] (glide! d x y (or ms (distance-ms x y))))
      :click       (let [[x y] args] (glide! d x y (distance-ms x y)) (Thread/sleep 120) (press! d))
      :double      (let [[x y] args] (glide! d x y (distance-ms x y)) (Thread/sleep 120) (press! d) (Thread/sleep 90) (press! d))
      :shift-click (let [[x y] args] (glide! d x y (distance-ms x y)) (x/key! d "Shift_L" true) (Thread/sleep 60)
                     (press! d) (x/key! d "Shift_L" false))
      :drag        (let [[x1 y1 x2 y2 ms] args]
                     (glide! d x1 y1 (distance-ms x1 y1)) (Thread/sleep 100)
                     (x/button! d 1 true) (Thread/sleep 80)
                     (glide! d x2 y2 ms) (Thread/sleep 80)
                     (x/button! d 1 false))
      :scroll      (let [[x y dir n] args b (if (= dir :up) 4 5)]
                     (glide! d x y (distance-ms x y))
                     (dotimes [_ n] (x/button! d b true) (x/button! d b false) (Thread/sleep 60)))
      :type        (type! d (first args))
      :key         (tap! d (first args))
      :keys        (do (doseq [k args] (x/key! d k true)) (Thread/sleep 40)
                       (doseq [k (reverse args)] (x/key! d k false)))
      :shot        (screenshot! display size (shot-file (first args)))
      (throw (ex-info (str "Unknown step " (pr-str step)) {:step step})))))

(defn- fit-window!
  "Finds the window and sizes it to the screen. A window already that size
  is nudged first, so the app lays itself out again."
  [d pattern [w h]]
  (let [win (loop [tries 50]
              (or (x/find-window d #(re-find (re-pattern pattern) %))
                  (if (pos? tries) (do (Thread/sleep 200) (recur (dec tries)))
                      (throw (ex-info (str "No window matching " pattern) {})))))]
    (x/place! d win 0 0 (- w 20) (- h 20))
    (Thread/sleep 700)
    (x/place! d win 0 0 w h)
    (Thread/sleep 1200)
    win))

(defn record!
  [{:keys [display shots out]}]
  (let [{:keys [window size fps steps]} (edn/read-string (slurp shots))
        [w h] size
        d     (x/display display)
        _     (fit-window! d window size)
        _     (io/make-parents out)
        base  (str/replace out #"\.[^.]+$" "")
        cmd   ["ffmpeg" "-v" "error" "-y" "-f" "x11grab" "-framerate" (str (or fps 30)) "-draw_mouse" "1"
               "-video_size" (str w "x" h) "-i" display
               "-c:v" "libx264" "-preset" "ultrafast" "-crf" "10" "-pix_fmt" "yuv420p" out]
        proc  (.start (doto (ProcessBuilder. ^java.util.List cmd) (.redirectErrorStream true)))
        t0    (System/nanoTime)
        marks (atom [])
        mark! (fn [name] (swap! marks conj [name (/ (- (System/nanoTime) t0) 1e9)])
                (println (format "%7.2f s  %s" (double (second (peek @marks))) name)))]
    (try
      (Thread/sleep 1000)
      (play! d display size steps mark! #(str base "." % ".png"))
      (Thread/sleep 800)
      (finally
        (with-open [in (.getOutputStream proc)] (.write in (.getBytes "q")))
        (.waitFor proc)))
    (spit (str base ".marks.edn") (pr-str (into {} @marks)))
    (println "Wrote" out "and" (str base ".marks.edn"))))

(defn -main [& args]
  (let [opts (into {} (map (fn [[k v]] [(keyword (subs k 2)) v])) (partition 2 args))]
    (doseq [k [:display :shots :out]]
      (when-not (get opts k) (println "trailer.record needs --" (name k)) (System/exit 2)))
    (record! opts)
    (shutdown-agents)))
