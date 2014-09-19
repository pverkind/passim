(ns passim
  (:require [clojure.string :as s]
            [clojure.set :as set]
            [clojure.data.csv :as csv]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.shell :as sh]
            [clojure.java.io :as jio]
            [ciir.utils :refer :all]
            [passim.galago :refer :all]
            [passim.utils :refer :all]
            [passim.quotes]
            [clojure.math.combinatorics :refer [combinations]])
  (:import (passim.utils Alignment)
           (org.lemurproject.galago.core.index IndexPartReader KeyIterator)
           (org.lemurproject.galago.core.index.disk DiskIndex)
           (org.lemurproject.galago.core.parse Document)
           (org.lemurproject.galago.core.retrieval Retrieval RetrievalFactory)
           (org.lemurproject.galago.tupleflow Parameters))
  (:gen-class))

(set! *warn-on-reflection* true)

(defn bill-doc
  [v]
  (str "<DOC>\n<DOCNO> " (second v) "/" (first v) " </DOCNO>\n<TEXT>\n"
       (s/replace (nth v 4) "\r\n" "\n")
       "</TEXT>\n</DOC>\n"))

(defn make-series-map
  [idx]
  (let [nidx (-> idx (jio/file "names.reverse") str)
        asize (inc (->> nidx dump-index (map #(Integer/parseInt (second %))) (reduce max)))
        res (make-array Integer/TYPE asize)]
    ;; (prn asize)
    (doseq [series (partition-by (comp doc-series first) (dump-index nidx))]
      (let [snum (Integer/parseInt (second (first series)))]
        (doseq [doc series]
          ;;(prn (Integer/parseInt (second doc)) snum)
          (aset-int res (Integer/parseInt (second doc)) snum))))
    #(get res %)))

(defn read-series-map
  [fname]
  (let [asize (-> (sh/sh "tail" "-1" fname) :out (s/split #"\t") first Integer/parseInt inc)
        res (make-array Integer/TYPE asize)]
    (with-open [in (jio/reader fname)]
      (doseq [line (line-seq in)]
        (let [[id series] (s/split line #"\t")]
          (aset-int res (Integer/parseInt id) (Integer/parseInt series)))))
    #(get res %)))

;; Sort by series, then multiply group sizes.
(defn cross-counts
  [series rec]
  (->> (nth rec 2)
       (map first)
       (map #(series %))
       frequencies
       vals
       (#(combinations % 2))
       (map (partial apply *))
       (reduce +)))

(defn cross-pairs
  [series upper max-df rec]
  (let [total-freq (second rec)]
    (when (<= total-freq upper)
      (let [k (first rec)
            npairs (cross-counts series rec)]
        (when (<= npairs upper)
          (let [docs (nth rec 2)]
            (for [[bid & brest] docs [aid & arest] docs
                  :while (< aid bid)
                  :when (and (not= (series aid) (series bid))
                             (<= (first arest) max-df)
                             (<= (first brest) max-df))]
              [[aid bid] [["" total-freq (second arest) (second brest)]]])))))))

(defn rand-blat
  [f prop coll]
  (filter
   #(do
      (when (<= (rand) prop)
        (binding [*out* *err*]
          (println (f %))))
      true)
   coll))

(defn merge-pairs
  "Merge postings for document pairs."
  [& argv]
  (let [[options remaining banner]
        (safe-cli argv
                  (str
                   "passim merge [options] <index>\n\n"
                   (var-doc #'merge-pairs))
                  ["-m" "--min-matches" "Minimum matching n-gram features" :default 1 :parse-fn #(Integer/parseInt %)]
                  ["-h" "--help" "Show help" :default false :flag true])
        {:keys [min-matches]} options
        recs (->> *in* jio/reader line-seq  (map edn/read-string) (partition-by first))]
    (doseq [rec recs]
      (let [k (ffirst rec)
            v (vec (mapcat second rec))]
        (when (>= (count v) min-matches)
          (prn [k v]))))))

(defn mean
  "Mean of sequence values"
  [s]
  (/ (reduce + s) (count s)))

;; It might be nice to add something like minimum average word
;; length. You'd want this to be greater than 1, perhaps at least 2,
;; no?  We could check this after alignment, as well, but this appears
;; to uncover a bug in the alignment algorithm where some text pairs
;; get out of alignment.
(defn dump-pairs
  "Output document pairs with overlapping features."
  [& argv]
  (let [[options remaining banner]
        (safe-cli argv
                  (str
                   "passim pairs [options] <index>\n\n"
                   (var-doc #'dump-pairs))
                  ["-c" "--counts" "Count pairs" :default false :flag true]
                  ["-u" "--max-series" "Upper limit on effective series size" :default 100 :parse-fn #(Integer/parseInt %)]
                  ["-d" "--max-df" "Maximum document frequency in posting lists" :default 100 :parse-fn #(Integer/parseInt %)]
                  ["-m" "--series-map" "Map internal ids documents to integer series ids"]
                  ["-p" "--modp" "Keep only features whose hashes are divisible by p" :default 1 :parse-fn #(Integer/parseInt %)]
                  ["-r" "--modrec" "Keep only pairs whose hashes are divisible by r" :default 1 :parse-fn #(Integer/parseInt %)]
                  ["-s" "--step" "Chunk of index to read" :default 0 :parse-fn #(Integer/parseInt %)]
                  ["-t" "--stride" "Size of index chunks" :default 1000 :parse-fn #(Integer/parseInt %)]
                  ["-w" "--word-length" "Minimum average word length" :default 1.5 :parse-fn #(Double/parseDouble %)]
                  ["-S" "--stop" "Stopword list"]
                  ["-h" "--help" "Show help" :default false :flag true])
        index-file ^String (first remaining)
        {:keys [counts series-map stop max-series max-df modp modrec step stride word-length]} options]
    (let [ireader (DiskIndex/openIndexPart index-file)
          ki (.getIterator ireader)
          idir (.getParent (java.io.File. index-file))
          series (if series-map
                   (read-series-map series-map)
                   (make-series-map idir))
          stops (if stop (-> stop slurp (s/split #"\n") set (disj "")) #{})
          upper (/ (* max-series (dec max-series)) 2)]
      (dorun (repeatedly (* step stride) (fn [] (.nextKey ki))))
      ;; (println "#" step stride (.getKeyString ki))
      (let [items (cond->>
                   (->> ki dump-kl-index (take stride))
                   (> modp 1) (filter #(= 0 (mod (.hashCode ^String (first %)) modp)))
                   (not-empty stops) (remove #(some stops (s/split (first %) #"~")))
                   (> word-length 1) (remove #(< (->> (s/split (first %) #"~") (map count) mean) word-length))
                   true (mapcat (partial cross-pairs series upper max-df))
                   (> modrec 1) (filter #(= 0 (mod (hash %) modrec))))]
        (if counts
          (let [sname (->> (jio/file idir "names") str dump-index
                           (map (fn [[k v]]
                                  [(Integer/parseInt k) (doc-series v)]))
                           (into {}))]
            (doseq [[[a b] c] (->> items
                                   (mapcat keys)
                                   (map #(vec (sort (map series %))))
                                   frequencies)]
              (println (format "%s\t%s\t%d" (sname a) (sname b) c))))
          (doseq [item items]
            (prn item)))))))

(defn- vappend
  [x y]
  (conj x (first y)))

(defn gap-postings
  [gap term-filter doc]
  (let [[id terms] doc
        len (count terms)]
    (into
     {}
     (map
      (fn [[k v]]
        (vector
         k [(vector id (count v) (vec v))]))
      (loop [i 0
             j (+ i (dec gap))
             posts {}]
        (if (>= j len)
          posts
          (recur (inc i)
                 (inc j)
                 (if (and (term-filter (terms i))
                          (term-filter (terms j)))
                   (merge-with vappend posts
                               {(str (terms i) "~" (terms j)) [i]})
                   posts))))))))

(defn- get-vocab
  [postings-file min-df max-df]
  (->> postings-file dump-index ;;(take 100000)
       (filter #(let [df (second %)]
                  (and (>= df min-df) (<= df max-df))))
       (map first)
       set))

(defn index-gaps
  [corpus-file postings-file gap min-df max-df step stride]
  (let [support (get-vocab postings-file min-df max-df)]
    (doseq [rec
            (->> corpus-file dump-corpus
                 (drop (* step stride)) (take stride)
                 (map (partial gap-postings gap support))
                 (reduce (partial merge-with vappend)))]
      (prn rec))))

(defn- spair
  [s]
  (vector (apply str (map first s)) (apply str (map second s))))

(defn word-substitutions
  [gram dict s1 s2]
  (let [target (- gram 2)]
    (->> (map vector (seq s1) (seq s2))
         (partition-by #{[\space \space]})
         (remove #{'([\space \space])})
         (map vec)
         (partition gram 1)
         (remove #(some #{\space} (flatten %)))
         (map #(map spair %))
         (filter
          (fn [x]
            (let [m (mapv (partial apply =) x)]
              (when (and (not (nth m target))
                         (= 1 (count (remove identity m))))
                ;;(when (and (first m) (second m) (not (nth m 2)) (nth m 3))
                (let [w1 (s/replace (first (nth x target)) "-" "")
                      w2 (s/replace (second (nth x target)) "-" "")
                      ] ;;diffs (remove (partial apply =) (map vector (seq (first (nth x 2))) (seq (second (nth x 2)))))]
                  (and
                   (> (count w1) 7)
                   (> (count w2) 7)
                   ;; Require edit distance > 1?
                   ;; (> (count diffs) 1)
                   ;; (not (prn diffs))
                   (dict w1)
                   (dict w2))))))))))

(defn score-pair
  [^String s ^Retrieval ri ^long gram]
  (let [[[id1 id2] matches] (edn/read-string s)
        name1 (.getDocumentName ri (int id1))
        name2 (.getDocumentName ri (int id2))
        words1 (doc-words ri name1)
        words2 (doc-words ri name2)
        approx-pass
        (try
          (if-let [p (seq (best-passages words1 words2 matches
                                         (if (= gram 0) 1 gram)))]
            ;; (reduce (maxer #(- (:end1 %) (:start1 %))) passages)
            p
            [(Alignment. "" "" 0 0 0 0)])
          (catch Exception e
            [(Alignment. "" "" 0 0 0 0)])
          (catch OutOfMemoryError e
            [(Alignment. "" "" 0 0 0 0)]))
        passages (if (= gram 0)
               (try
                 [(swg-align words1 words2)]
                 (catch Exception e
                   (.println System/err e)
                   approx-pass)
                 (catch OutOfMemoryError e
                   (.println System/err e)
                   approx-pass))
               approx-pass)]
        ;; nseries (count smeta)
        ;; idf (reduce +
        ;;             (map #(Math/log %)
        ;;                  (map (partial / nseries) (map first (vals matches)))))
    (for [pass passages :when (>= (- (:end1 pass) (:start1 pass)) gram)]
      (let [match-len1 (- (:end1 pass) (:start1 pass))
            match-len2 (- (:end2 pass) (:start2 pass))]
        (s/join "\t" (concat [match-len1
                              (float (/ match-len1 (count words1)))
                              (float (/ match-len2 (count words2)))]
                             ((juxt :matches :gaps :swscore) (alignment-stats pass))
                             [id1 id2 name1 name2
                              (:start1 pass) (:end1 pass)
                              (:start2 pass) (:end2 pass)
                              (-> pass :sequence1 s/trim)
                              (-> pass :sequence2 s/trim)]))))))

(defn load-series-meta
  [fname]
  (->> fname jio/reader line-seq
       (map #(let [fields (s/split % #"\t")]
               [(nth fields 3) (nth fields 2)]))
       (into {})))

(defn load-tab-map
  [fname]
  (->> fname jio/reader line-seq
       (map #(s/split % #"\t" 2))
       (into {})))

;; (def ri (RetrievalFactory/instance "/Users/dasmith/locca/ab/build/idx" (Parameters.)))
;; (def qwe (line-seq (jio/reader "/Users/dasmith/locca/ab/build/pairs/pall.1k")))

(defn dump-scores
  "Score document pairs based on n-gram overlap"
  [& argv]
  (let [[options remaining banner]
        (safe-cli argv
                  (str
                   "passim scores [options] <index>\n\n"
                   (var-doc #'dump-scores))
                  ["-n" "--ngram" "N-gram order" :default 5 :parse-fn #(Integer/parseInt %)]
                  ["-h" "--help" "Show help" :default false :flag true])
        idx ^String (first remaining)
        gram (:ngram options)]
  (let [ri (RetrievalFactory/instance idx (Parameters.))]
    (doseq [line (-> *in* jio/reader line-seq)]
      (doseq [out (score-pair line ri gram)]
        (println out))))))

(defn jaccard
  [set1 set2]
  (/ (count (set/intersection set1 set2)) (count (set/union set1 set2))))

(defn complete-cluster-matches
  [m thresh voc id]
  (let [members (get-in m [:members id])]
    (= (count members)
       (count
        (filter
         #(> (jaccard voc (:vocabulary %)) thresh)
         members)))))

(defn single-cluster-matches
  [m thresh voc id]
  (let [members (get-in m [:members id])]
    (<= 1
        (count
         (filter
          #(> (jaccard voc (:vocabulary %)) thresh)
          members)))))

(defn absolute-overlap
  [rec1 rec2]
  (let [s1 ^long (:start rec1)
        e1 ^long (:end rec1)
        s2 ^long (:start rec2)
        e2 ^long (:end rec2)]
    (- (min e1 e2) (max s1 s2))))

(defn span-overlap
  [rec1 rec2]
  (let [s1 ^long (:start rec1)
        e1 ^long (:end rec1)
        s2 ^long (:start rec2)
        e2 ^long (:end rec2)
        len1 (double (- e1 s1))
        len2 (double (- e2 s2))
        shorter (double (min len1 len2))]
    (/ (max 0 (- (min e1 e2) (max s1 s2)))
       (max len1 len2))))

(defn single-link-matches
  [match-fn thresh m clusters1 clusters2 rec1 rec2]
  (let [id1 (:id rec1)
        id2 (:id rec2)]
    (set/union
     (set
      (filter #(>= (match-fn rec1 (get-in m [:members % id1])) thresh) clusters1))
     (set
      (filter #(>= (match-fn rec2 (get-in m [:members % id2])) thresh) clusters2)))))

(defn greedy-cluster-reducer
  [match-fn m line]
  (let [[sscore prop1 prop2 matches gaps ascore sid1 sid2 name1 name2 s1 e1 s2 e2 raw1 raw2]
        (s/split line #"\t")
        id1 (Integer/parseInt sid1)
        id2 (Integer/parseInt sid2)
        score (Double/parseDouble sscore)
        rec1 {:id id1 :name name1 :series (doc-series name1) :score score
              :start (Long/parseLong s1) :end (Long/parseLong e1) :text nil}
        rec2 {:id id2 :name name2 :series (doc-series name2) :score score
              :start (Long/parseLong s2) :end (Long/parseLong e2) :text nil}
        nextid (inc (get m :top 0))
        clusters1 (get-in m [:clusters id1] #{})
        clusters2 (get-in m [:clusters id2] #{})
        matches (match-fn m clusters1 clusters2 rec1 rec2)
        match (or (first matches) nextid)]
    (assoc
        (if (> (count matches) 1)
          (let [others (rest matches)
                orecs (map (partial get (:members m)) others)
                newrec (merge {id1 rec1 id2 rec2} (reduce merge orecs) (get-in m [:members match]))
                docs (keys newrec)
                newidx
                (into
                 {} (map
                     vector docs
                     (map #(conj (apply disj (get (:clusters m) % #{}) others) match) docs)))]
            ;; (println match "\t" others)
            ;; (println id1 "clusters1:" clusters1)
            ;; (println id2 "clusters2:" clusters2)
            ;; (println "docs:" docs)
            ;; (println "newidx:" newidx)
            ;; Need to dissociate the old cluster numbers from
            ;; *all* documents, not just id1 and id2
            ;; To test, take the first 877
            (-> m
                ;; need to dissoc old members entries
                (assoc :members (apply dissoc (:members m) others))
                (assoc-in [:members match] newrec)
                (assoc :clusters (merge (:clusters m) newidx))))
          (-> m
              (assoc-in [:members match] (merge {id1 rec1 id2 rec2} (get-in m [:members match])))
              (assoc-in [:clusters id1] (conj clusters1 match))
              (assoc-in [:clusters id2] (conj clusters2 match))))
      :top nextid)))

(defn- cluster-member-text
  [^Retrieval ri rec]
  (s/join " " (subvec (doc-words ri (:name rec)) (:start rec) (:end rec))))

(defn dump-cluster
  [cluster]
  [(->> cluster (map :name) set count)
   (mapv
    #((juxt :name :start :end) %)
    cluster)])

(defn top-rep-cluster
  [cluster]
  (->> cluster
       ;; NB: Not unique series, but same series with multiple IDs.
       (map (juxt :id :series))
       (into {})
       vals
       frequencies
       vals
       (reduce max)))

(defn cluster-scores
  "Single-link clustering of reprints"
  [& argv]
  (let [[options remaining banner]
        (safe-cli argv
                  (str
                   "passim cluster [options]\n\n"
                   (var-doc #'cluster-scores))
                  ["-m" "--min-overlap" "Minimum size of overlap" :default 0 :parse-fn #(Double/parseDouble %)]
                  ["-o" "--relative-overlap" "Proportion of longer text that must overlap" :default 0.5 :parse-fn #(Double/parseDouble %)]
                  ["-p" "--max-proportion" "Maximum proportion of cluster from one series" :default 1.0 :parse-fn #(Double/parseDouble %)]
                  ["-r" "--max-repeats" "Maximum number of texts from one series" :default 4 :parse-fn #(Integer/parseInt %)]
                  ["-h" "--help" "Show help" :default false :flag true])
        lines (-> *in* jio/reader line-seq)
        {:keys [min-overlap relative-overlap max-proportion max-repeats]} options]
    (doseq
        [cluster
         (->> lines
              (reduce (partial greedy-cluster-reducer
                               (if (> min-overlap 0)
                                 (partial single-link-matches absolute-overlap min-overlap)
                                 (partial single-link-matches span-overlap relative-overlap)))
                      {})
              :members
              vals
              (map vals)
              (remove
               (if (< max-proportion 1)
                 #(> (double (/ (top-rep-cluster %) (count %))) max-proportion)
                 #(> (top-rep-cluster %) max-repeats)))
              (map dump-cluster)
              (sort (comp - compare))
              (map-indexed
               #(hash-map :id (inc %1) :size (first %2) :members (second %2))))]
      (json/write cluster *out* :escape-slash false)
      (println))))

(defn format-cluster
  "Format cluster data"
  [& argv]
  (let [[options remaining banner]
        (safe-cli argv
                  (str
                   "passim format [options] <index>\n\n"
                   (var-doc #'format-cluster))
                  ["-h" "--help" "Show help" :default false :flag true])
        idx ^String (first remaining)
        lines (-> *in* jio/reader line-seq)
        ri (RetrievalFactory/instance idx (Parameters.))]
    (doseq [line lines]
      (let [{:keys [id size members]} (json/read-str line :key-fn keyword)]
        (json/pprint
         {:id id :size size
          :members
          (sort-by
           :date
           (for [[name start end] members]
             (let [m (doc-meta ri name)
                   base-url (m "url")
                   text (doc-text ri name start end)
                   url (if (re-find #"<w p=" text)
                         (loc-url base-url text)
                         base-url)
                   pretty-text
                   (-> text
                       (s/replace #"</?[a-zA-Z][^>]*>" "")
                       (s/replace #"\t" " ")
                       (s/replace #"\n" "<br/>"))]
               {:date (m "date")
                :id name
                :name (doc-series name)
                :title (m "title")
                :url url
                :start start :end end
                :text pretty-text})))}
         :escape-slash false :escape-unicode false)
        (println)))))

(defn median
  [coll]
  (let [c (count coll)]
    (nth (sort coll) (int (/ c 2)))))

(defn median-cluster-year
  [members]
  (->> members
       (map :date)
       (map #(Integer/parseInt (re-find #"^[0-9]{4}" %)))
       median))

(defn max-year
  [members]
  (->> members
       (map :date)
       (map #(Integer/parseInt (re-find #"[0-9]{4}" %)))
       (reduce max)))

(defn json-seq
  "Returns JSON records from rdr as a lazy sequence.
  rdr must implement java.io.BufferedReader."
  [^java.io.BufferedReader rdr]
  (when-let [rec (json/read rdr :key-fn keyword :eof-error? false)]
    (cons rec (lazy-seq (json-seq rdr)))))

(defn gexf-stats
  [recs binf]
  (->> recs
       (mapcat
        (fn [rec]
          (let [{:keys [id size members]} rec]
            (map
             #(conj (vec (sort (map :name %))) (binf %))
             (combinations members 2)))))
       frequencies))

(defn gexf-cluster
  "Produce GEXF from cluster data for display by Gephi"
  [& argv]
  (let [[options remaining banner]
        (safe-cli argv
                  (str
                   "passim gexf [options] <index>\n\n"
                   (var-doc #'gexf-cluster))
                  ["-h" "--help" "Show help" :default false :flag true])
        info (json/read (jio/reader (first remaining)) :key-fn keyword)
        labels (into {}
                     (concat
                      (map #(vector (str (:id %)) (:master_name %)) info)
                      (->> info (mapcat :publication_names)
                           (map #(vector (str (:sn %)) (:name %))))))
        bins (gexf-stats (-> *in* jio/reader json-seq) max-year)]
    (println "<gexf>")
    (println "<graph defaultedgetype=\"undirected\" mode=\"dynamic\" timeformat=\"date\">")
    (println "<nodes>")
    (doseq [n (set (mapcat (fn [[[s t b] w]] [s t]) bins))]
      (printf
       "<node id=\"%s\" label=\"%s\" />\n"
       n (get labels n n)))
    (println "</nodes>\n<edges>")
    (doseq [[[s t b] w] bins]
      (printf
       "<edge id=\"%s--%s--%d\" source=\"%s\" target=\"%s\" weight=\"%d\" start=\"%d\" label=\"%d\" />\n"
       s t b s t w b b))
    (println "</edges>\n</graph>\n</gexf>")))

(defn idtab-cluster
  "Produce idtab format from cluster data for Viral Texts website"
  [& argv]
  (let [[options remaining banner]
        (safe-cli argv
                  (str
                   "passim idtab [options]\n\n"
                   (var-doc #'gexf-cluster))
                  ["-h" "--help" "Show help" :default false :flag true])]
    (doseq [cluster (-> *in* jio/reader json-seq)]
      (let [prefix ((juxt :id :size) cluster)]
        (doseq [reprint (:members cluster)]
          (println
           (s/join "\t"
                   (concat
                    prefix
                    ((juxt :date :name :title :url :start :end :text) reprint)))))))))

(defn diff-words
  [gram lines]
  (let [dict (set (line-seq (jio/reader "/usr/share/dict/words")))]
    (doseq [line lines]
      (let [[sscore prop1 prop2 matches gaps ascore sid1 sid2 name1 name2 s1 e1 s2 e2 raw1 raw2]
            (s/split line #"\t")
            date1 (doc-date name1)
            date2 (doc-date name2)
            diffs (word-substitutions gram dict raw1 raw2)]
        (when (> (count diffs) 0)
          (doseq [diff diffs]
            (let [o1 (s/join " " (map first diff))
                  o2 (s/join " " (map second diff))]
              (println
               (s/join
                "\t"
                (if (< (compare date1 date2) 0)
                  [sscore date1 date2 o1 o2 name1 name2]
                  [sscore date2 date1 o2 o1 name2 name1]))))))))))

(defn -main
  "Usage: passim command [command-options]"
  [& argv]
  (let [commands
        {"pairs" #'dump-pairs
         "merge" #'merge-pairs
         "scores" #'dump-scores
         "cluster" #'cluster-scores
         "format" #'format-cluster
         "gexf" #'gexf-cluster
         "idtab" #'idtab-cluster
         "qoac" #'passim.quotes/qoac
         "quotes" #'passim.quotes/dump-quotes}
        usage
        (str
         (var-doc #'-main)
         "\n\nCommands:\n"
         (s/join
          "\n"
          (map
           (fn [[k v]]
             (str "\t" k "\t\t" (var-doc v)))
           commands)))]
    (if (seq argv)
      (let [[cmd & args] argv]
        (if-let [v (commands cmd)]
          (apply v args)
          (exit 1 usage)))
      (exit 1 usage))))

      ;;   (condp = cmd
      ;;     "diffs" (diff-words
      ;;              (Long/parseLong (first args))
      ;;              (-> System/in java.io.InputStreamReader. java.io.BufferedReader. line-seq))
      ;;     ;; These are for debugging and aren't used much.
      ;;     "gaps" (index-gaps (first args) (second args)
      ;;                        (Integer/parseInt (nth args 2)) (Integer/parseInt (nth args 3))
      ;;                        (Integer/parseInt (nth args 4)) (Integer/parseInt (nth args 5))
      ;;                        (Integer/parseInt (nth args 6)))                       
      ;;     "counts" (->> (first args) dump-index (map second) frequencies prn)
      ;;     "entries"  (->> (first args) dump-index count prn)
      ;;     "total"  (->> (first args) dump-index (rand-blat first 0.001) (map second) (reduce +) prn)
      ;;     "dump" (doseq
      ;;                [s (->> (first args) dump-index)]
      ;;              (println s))
      ;;     "easy-dump" (kv-dump (DiskIndex/openIndexPart (first args)))
      ;;     (exit 1 usage)
      ;; (exit 1 usage))))))
