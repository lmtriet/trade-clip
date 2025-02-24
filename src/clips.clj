#!/usr/bin/env bb

(require '[babashka.deps :as deps])

(require '[cheshire.core :as json]
         '[babashka.fs :as fs]
         '[babashka.process :as proc])

(def config
  {:tag-group "Setups"  ; Name of the tag group
   :extracting-clips? true
   :pre-buffer 30   ; Seconds before entry
   :post-buffer 60  ; Seconds after exit
   :ffmpeg-path "ffmpeg"
   :upload-youtube? true
   :client-id ""
   :client-secret ""
   :access-token-file "./access_token"
   :refresh-token-file "./refresh_token"})

(defn find-setup-tags [tag-groups tags]
  (let [setup-group-id (->> tag-groups
                            (filter #(= (:tag-group config) (:name %)))
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
      fs/normalize    ; Use babashka.fs for cross-platform normalization
      fs/file
      .getName))

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

(defn ffmpeg-exists? []
  (some? (fs/which (:ffmpeg-path config))))

(defn generate-trade-clips
  "Generate trade clips with :pre-buffer before entry and :post-buffer after exit.
   Uses FFmpeg to copy streams without re-encoding. Returns output path for diagnostics."
  [trade]
  (when-not (:extracting-clips? config)
    (println "Extracting clips disabled")
    (System/exit 0))

  (when-not (ffmpeg-exists?)
    (binding [*out* *err*]
      (println "ERROR: FFmpeg executable not found in PATH"))
    (System/exit 1))

  (let [{:keys [ffmpeg-path]} config
        {:keys [filepath entry-zdt exit-zdt symbol_id setup_tags pnl rating]} trade
        video-dir (fs/parent filepath)

        ;; Calculate time parameters
        clip-start (parse-obs-filename (get-filename-from-path filepath))
        entry-secs (.getSeconds (java.time.Duration/between clip-start entry-zdt))
        exit-secs (.getSeconds (java.time.Duration/between clip-start exit-zdt))
        start-secs (max 0 (- entry-secs (:pre-buffer config)))
        duration-secs (+ (- exit-secs entry-secs)
                         (:pre-buffer config)
                         (:post-buffer config))

        ;; Generate filename components
        safe-setup (str/join "+" (map #(str/replace % #"\s+" "_") setup_tags))
        dt-formatter (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd_HH-mm-ss")
        filename (format "%s_%s_%s_%s_%s.mp4"
                         (.format entry-zdt dt-formatter)
                         symbol_id
                         safe-setup
                         (format-pnl pnl)
                         (format-rating rating))
        output-path (str video-dir fs/file-separator filename)]

    ;; Execute FFmpeg with clean error handling
    (try
      (println "Extracting clip" output-path)
      (proc/shell {:dir video-dir
                   :err :inherit
                   :out :inherit}
                  ffmpeg-path
                  "-hide_banner" "-loglevel" "error" "-y"
                  "-ss" (str start-secs)
                  "-i" filepath
                  "-t" (str duration-secs)
                  "-c" "copy"
                  output-path)
      {:output-path output-path :duration-secs duration-secs}
      (catch Exception e
        (binding [*out* *err*]
          (println "FFmpeg processing failed for trade:" symbol_id)
          (println "Error:" (.getMessage e)))
        nil))))

(defn normalize-filename [filename]
  (str/replace filename
               #"(\d{4}) (\d{2}) (\d{2}) (\d{2}) (\d{2}) (\d{2})\.mp4"
               "$1-$2-$3 $4-$5-$6.mp4"))

;; TODO
(defn check-tokens-or-authenticate
  "Check if access token exists and still valid. If not use the refresh token to get a new one.
  If nothing exist or valid. Authenticate to Youtube OAuth and save the tokens for later use.
  Return the access token to upload videos"
  [])

;; TODO
(defn ensure-playlist-exist
  "Ensure that the playlist exist. Create a new one if not. The created playlist is unlisted."
  [access-token playlist-name])

;; TODO
(defn upload-clip-youtube
  "Upload the trade clip, unlisted, to youtube. The playlist is the setup name."
  [clip-file trade]
  (when (:upload-youtube? config)
    ;; todo
    ))

(defn merge-clips
  "Parameters clips is a list of map {:output-path output-path :duration-secs duration-secs}, trade-day is the current trade-day
  This function merges the clips into one file named {trade-day}_AllTrades.mp4 using FFMPEG. Generate the timecode pastable to Youtube."
  [trade-day clips]
  (let [{:keys [ffmpeg-path]} config
        ;; Filter out any nil clips from failed processing
        valid-clips (filter :output-path clips)
        output-filename (format "%d_AllTrades.mp4" trade-day)
        output-path (-> (get-in (first valid-clips) [:output-path])
                        fs/parent
                        (fs/file output-filename)
                        str)

        ;; Generate chapter list with accumulated timestamps
        chapters (->> valid-clips
                      (reduce (fn [{:keys [acc-time] :as state} {:keys [output-path duration-secs]}]
                                (let [chapter-name (-> output-path fs/file-name (str/replace #"\.mp4$" ""))
                                      start-time (seconds->timecode acc-time)
                                      end-time (seconds->timecode (+ acc-time duration-secs))]
                                  {:acc-time (+ acc-time duration-secs)
                                   :lines (conj (:lines state)
                                                (format "%s %s - %s" start-time chapter-name end-time))}))
                              {:acc-time 0 :lines []})
                      :lines
                      (str/join "\n"))]

    (when (seq valid-clips)
      ;; Create temporary file listing clips for concat
      (let [list-file (str (fs/create-temp-file {:prefix "ffmpeg-list-" :suffix ".txt"}))]
        (try
          ;; Write FFmpeg concat list
          (spit list-file
                (str/join "\n"
                          (map #(format "file '%s'" (-> % :output-path fs/absolutize str))
                               valid-clips)))

          ;; Execute FFmpeg merge command
          (proc/shell {:err :inherit :out :inherit}
                      ffmpeg-path
                      "-y"
                      "-f" "concat"
                      "-safe" "0"
                      "-i" (str list-file)
                      "-c" "copy"
                      output-path)

          ;; Write chapters to text file
          (let [chapters-file (str (fs/parent output-path) fs/file-separator "chapters.txt")]
            (spit chapters-file chapters)
            (println (str "Merged video: " output-path))
            (println (str "Youtube Chapters saved to: " chapters-file))
            (println chapters))


          (finally
            (fs/delete list-file)))))))


(defn -main [args]
  (when (not= 1 (count args))
    (println "Usage: clips.clj \"C:\\Users\\slimdog\\Videos\\2025-02-21 13-25-30.mp4\"")
    (System/exit 1))

  (let [filepath   (fs/normalize (first args))
        filename   (normalize-filename (get-filename-from-path filepath))
        trade-day (filename->date-int filename)
        clip-start (parse-obs-filename filename)
        data (-> (slurp "dataExport.json")
                 (json/parse-string true))
        setup-tags (find-setup-tags (:tagGroup data) (:tag data))
        formatter (-> (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss")
                      (.withZone (java.time.ZoneId/systemDefault)))
        processed-trades (->> (:trade data)
                              (filter #(= trade-day (:tradeDay %)))
                              (map (fn [t]
                                     (let [entry-instant (java.time.Instant/ofEpochMilli (:entryTimestamp t))
                                           exit-instant (java.time.Instant/ofEpochMilli (:exitTimestamp t))
                                           entry-zdt (.atZone entry-instant (.getZone clip-start))
                                           exit-zdt (.atZone exit-instant (.getZone clip-start))
                                           duration (java.time.Duration/between clip-start entry-zdt)
                                           total-secs (max 0 (.getSeconds duration)) ;; Enforce >= 0
                                           ]
                                       {:filepath filepath
                                        :entry_timestamp (.format formatter entry-instant)
                                        :entry-zdt entry-zdt
                                        :exit-zdt exit-zdt
                                        :symbol_id (:symbolId t)
                                        :size (:size t)
                                        :start-offset (seconds->timecode total-secs)  ;; Formatted timecode
                                        :pnl (:pnl t)
                                        :direction (if (pos? (:direction t)) :L :S)
                                        :setup_tags (keep setup-tags (:tagIds t))
                                        :rating (:rating t)}))))]

    (->> processed-trades
         (sort-by :start-offset)
         (map format-chapter-line)
         (str/join "\n")
         (println))
    (merge-clips trade-day (->> processed-trades
                                (sort-by :entry-zdt)
                                (map generate-trade-clips)))))

(-main *command-line-args*)