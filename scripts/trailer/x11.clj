;; Copyright 2026 EchoJustus. Part of clogem-support.
;; SPDX-License-Identifier: MIT
(ns trailer.x11
  "Just enough of Xlib and XTest, through the JDK's foreign function API, to
  drive an app on an X display (Xvfb) the way a person would: find its
  window, size it, move the pointer, click, scroll and type. JVM only
  (java.lang.foreign, JDK 22 or later), started with
  --enable-native-access=ALL-UNNAMED (deps.edn, :record).

  Every event goes through XTest, so the app sees ordinary input: hover,
  press and release, keys with their modifiers."
  (:import (java.lang.foreign Arena FunctionDescriptor Linker Linker$Option MemoryLayout
                              MemorySegment SymbolLookup ValueLayout ValueLayout$OfInt
                              ValueLayout$OfLong AddressLayout)
           (java.lang.invoke MethodHandle)))

(set! *warn-on-reflection* true)

(def ^:private ^Linker linker (delay (Linker/nativeLinker)))

(defn- lib ^SymbolLookup [^String name] (SymbolLookup/libraryLookup name (Arena/global)))

(def ^:private libs (delay {:x11 (lib "libX11.so.6") :xtst (lib "libXtst.so.6")}))

(def ^:private ^AddressLayout A ValueLayout/ADDRESS)
(def ^:private ^ValueLayout$OfInt I ValueLayout/JAVA_INT)
(def ^:private ^ValueLayout$OfLong L ValueLayout/JAVA_LONG)

(defn- handle ^MethodHandle [lib-key ^String name ret args]
  (let [sym  (.orElseThrow (.find ^SymbolLookup (get @libs lib-key) name))
        args (into-array MemoryLayout args)
        desc (if ret (FunctionDescriptor/of ret args) (FunctionDescriptor/ofVoid args))]
    (.downcallHandle ^Linker @linker ^MemorySegment sym desc (make-array Linker$Option 0))))

(def ^:private fns
  (delay
    {:open      (handle :x11 "XOpenDisplay" A [A])
     :root      (handle :x11 "XDefaultRootWindow" L [A])
     :tree      (handle :x11 "XQueryTree" I [A L A A A A])
     :name      (handle :x11 "XFetchName" I [A L A])
     :geometry  (handle :x11 "XGetGeometry" I [A L A A A A A A A])
     :resize    (handle :x11 "XMoveResizeWindow" I [A L I I I I])
     :free      (handle :x11 "XFree" I [A])
     :sync      (handle :x11 "XSync" I [A I])
     :keysym    (handle :x11 "XStringToKeysym" L [A])
     :keycode   (handle :x11 "XKeysymToKeycode" ValueLayout/JAVA_BYTE [A L])
     :motion    (handle :xtst "XTestFakeMotionEvent" I [A I I I L])
     :button    (handle :xtst "XTestFakeButtonEvent" I [A I I L])
     :key       (handle :xtst "XTestFakeKeyEvent" I [A I I L])}))

(defn- call [k & args] (.invokeWithArguments ^MethodHandle (get @fns k) ^java.util.List (vec args)))

(defn display
  "Opens the display DISPLAY names (or `name`)."
  ([] (display nil))
  ([name]
   (let [arena (Arena/ofAuto)
         d     ^MemorySegment (call :open (if name (.allocateFrom arena ^String name) MemorySegment/NULL))]
     (when (= MemorySegment/NULL d) (throw (ex-info (str "Can't open display " (or name (System/getenv "DISPLAY"))) {})))
     d)))

(defn- children [d ^long w]
  (with-open [arena (Arena/ofConfined)]
    (let [root   (.allocate arena ^MemoryLayout L)
          parent (.allocate arena ^MemoryLayout L)
          kids   (.allocate arena ^MemoryLayout A)
          n      (.allocate arena ^MemoryLayout I)]
      (call :tree d w root parent kids n)
      (let [count (.get n I 0)
            ptr   (.get kids A 0)
            ws    (if (zero? count) []
                      (let [arr (.reinterpret ptr (* 8 count))]
                        (mapv #(.getAtIndex arr L (long %)) (range count))))]
        (when-not (= MemorySegment/NULL ptr) (call :free ptr))
        ws))))

(defn- window-name [d ^long w]
  (with-open [arena (Arena/ofConfined)]
    (let [out (.allocate arena ^MemoryLayout A)]
      (when-not (zero? (int (call :name d w out)))
        (let [p (.get out A 0)]
          (when-not (= MemorySegment/NULL p)
            (let [s (.getString (.reinterpret p 4096) 0)] (call :free p) s)))))))

(defn find-window
  "The first window, depth first from the root, whose name `pred` accepts."
  [d pred]
  (let [root (long (call :root d))]
    (loop [todo [root]]
      (when-let [w (peek todo)]
        (if (some-> (window-name d w) pred)
          w
          (recur (into (pop todo) (children d w))))))))

(defn geometry
  "The window's {:x :y :width :height}, relative to its parent."
  [d ^long w]
  (with-open [arena (Arena/ofConfined)]
    (let [cell (fn ^MemorySegment [^MemoryLayout l] (.allocate arena l))
          root (cell L) x (cell I) y (cell I) wd (cell I) ht (cell I) bw (cell I) depth (cell I)]
      (call :geometry d w root x y wd ht bw depth)
      {:x (.get ^MemorySegment x I 0) :y (.get ^MemorySegment y I 0)
       :width (.get ^MemorySegment wd I 0) :height (.get ^MemorySegment ht I 0)})))

(defn place!
  "Moves and sizes window `w`."
  [d w x y width height]
  (call :resize d (long w) (int x) (int y) (int width) (int height))
  (call :sync d (int 0)))

(defn move! [d x y] (call :motion d (int -1) (int x) (int y) 0) (call :sync d (int 0)))

(defn button! [d n press?] (call :button d (int n) (int (if press? 1 0)) 0) (call :sync d (int 0)))

(defn- keycode [d ^String keysym-name]
  (with-open [arena (Arena/ofConfined)]
    (let [sym (long (call :keysym (.allocateFrom arena keysym-name)))]
      (when (zero? sym) (throw (ex-info (str "No keysym " keysym-name) {})))
      (bit-and 0xff (long (call :keycode d sym))))))

(defn key!
  "Presses or releases the key with X keysym name `k` (\"Return\", \"a\")."
  [d k press?]
  (call :key d (int (keycode d k)) (int (if press? 1 0)) 0)
  (call :sync d (int 0)))

(def ^:private symbols
  {\space "space" \. "period" \, "comma" \: "colon" \; "semicolon" \- "minus" \_ "underscore"
   \' "apostrophe" \" "quotedbl" \! "exclam" \? "question" \/ "slash" \( "parenleft" \) "parenright"
   \& "ampersand" \@ "at" \# "numbersign" \+ "plus" \= "equal" \newline "Return"})

(def ^:private shifted (set ":_\"!?()&@#+"))

(defn char-key
  "The keysym name and shift for character `c`."
  [c]
  (cond
    (Character/isUpperCase (char c)) [(str (Character/toLowerCase (char c))) true]
    (Character/isLetterOrDigit (char c)) [(str c) false]
    (symbols c) [(symbols c) (contains? shifted c)]
    :else (throw (ex-info (str "Can't type " (pr-str c)) {}))))
