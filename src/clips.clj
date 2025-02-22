#!/usr/bin/env bb

(require '[babashka.deps :as deps])

(deps/add-deps '{:deps {ffclj/ffclj {:mvn/version "0.1.2" }}})

(require '[clojure.java.io :as io]
         '[cheshire.core :as json])

(defn find-setup-tags [tag-groups tags]
  (let [setup-group-id (->> tag-groups
                            (filter #(= "Setups" (:name %)))
                            first
                            :tagGroupId)]
    (->> tags
         (filter #(= setup-group-id (:tagGroupId %)))
         (reduce (fn [m t] (assoc m (:tagId t) (:name t))) {}))))

(defn parse-obs-filename [filename]
  (let [pattern (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH-mm-ss")
        clean-name (clojure.string/replace filename #"\.mp4$" "")
        local-dt (java.time.LocalDateTime/parse clean-name pattern)
        system-zone (java.time.ZoneId/systemDefault)]
    (.atZone local-dt system-zone)))

(defn filename->date-int
  "Converts date portion of filename to integer YYYYMMDD format"
  [filename]
  (-> filename
      (str/split #"\s" 2)  ; Split on first space
      first                ; Get date part "2025-02-20"
      (str/replace #"-" "") ; Remove hyphens
      (Long/parseLong)))    ; Convert to long

(defn get-filename-from-path
  "Extracts filename from Windows/Unix path"
  [filepath]
  (-> filepath
      (str/replace #"[/\\]+" "/")    ; Normalize path separators
      (str/split #"/")
      last))

(defn seconds->timecode [seconds]
  "Convert the duration to Youtube timecode, set it 30s before the entry time"
  (let [abs-secs (Math/abs (- seconds 30))
        hours (quot abs-secs 3600)
        remainder (mod abs-secs 3600)
        minutes (quot remainder 60)
        seconds (mod remainder 60)]
    (format "%02d:%02d:%02d" hours minutes seconds)))

(defn format-rating [rating]
  "Chapter formatting functions"
  (str "R" rating))

(defn format-pnl [pnl]
  (let [rounded (Math/round (double pnl))]
    (cond
      (zero? rounded) "0"
      :else (str (if (pos? rounded) "+" "-") (Math/abs rounded)))))

(defn format-chapter-line [{:keys [start-offset entry_timestamp symbol_id size direction pnl rating setup_tags]}]
  (let [time-part (subs entry_timestamp 11 19) ; Extract HH:mm:ss
        direction-flag (if (= direction :long) "L" "S")
        setup-str (when (seq setup_tags)
                    (str " - " (str/join ", " setup_tags)))]
    (str start-offset " "
         time-part " "
         symbol_id " "
         size ""
         direction-flag " "
         (format-pnl pnl) " "
         (format-rating rating)
         setup-str)))

(defn -main [args]
  (when (not= 1 (count args))
    (println "Usage: clips.clj \"C:\\Users\\slimdog\\Videos\\2025-02-21 13-25-30.mp4\"")
    (System/exit 1))


  (let [filepath (first args)
        filename (get-filename-from-path filepath)
        trade-day (filename->date-int filename)
        clip-start (parse-obs-filename filename)
        data (-> (slurp "dataExport.json")
                 (json/parse-string true))
        setup-tags (find-setup-tags (:tagGroup data) (:tag data))
        formatter (-> (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss")
                      (.withZone (java.time.ZoneId/systemDefault)))]

    (->> (:trade data)
         (filter #(= trade-day (:tradeDay %)))
         (map (fn [t]
                (let [entry-instant (java.time.Instant/ofEpochMilli (:entryTimestamp t))
                      entry-zdt (.atZone entry-instant (.getZone clip-start))
                      duration (java.time.Duration/between clip-start entry-zdt)
                      total-secs (max 0 (.getSeconds duration))] ;; Enforce >= 0
                  {:entry_timestamp (.format formatter entry-instant)
                   :symbol_id (:symbolId t)
                   :size (:size t)
                   :start-offset (seconds->timecode total-secs)  ;; Formatted timecode
                   :pnl (:pnl t)
                   :direction (if (pos? (:direction t)) :L :S)
                   :setup_tags (keep setup-tags (:tagIds t))
                   :rating (:rating t)})))
         (sort-by :start-offset)
         (map format-chapter-line)
         (str/join "\n")
         #_(json/generate-string)
         (println))))

(-main *command-line-args*)