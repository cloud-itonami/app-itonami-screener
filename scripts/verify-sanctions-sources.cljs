#!/usr/bin/env nbb
;; scripts/verify-sanctions-sources.cljs
;;
;; Checks data/sanctions-sources.edn against the world it cites and against the
;; source tree it claims to describe.
;;
;;   exit 0  every citation resolved and every code constant agreed
;;   exit 1  a citation broke, or the tree and the catalog disagree  (FAIL)
;;   exit 3  the check could not be run at all                       (INCONCLUSIVE)
;;
;; Three is not a stylistic choice. A catalog of remote URLs cannot distinguish
;; "the citations are fine" from "I have no network" if both leave through the
;; same exit code, and the second one silently accumulates as green. Anything
;; that prevents an answer -- missing file, unreadable EDN, dead egress -- exits
;; 3 and says so, and no run without a SCANNED line may be read as clean.
;;
;; Usage:
;;   nbb scripts/verify-sanctions-sources.cljs [<dir>] [--offline]
;;
;; <dir> defaults to the repository root inferred from this script's location and
;; must come FIRST if given; --offline skips the network leg and still checks the
;; catalog/tree agreement (it exits 3, never 0, because it did not measure).

(ns verify-sanctions-sources
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            ["fs" :as fs]
            ["path" :as path]))

;; *command-line-args* is nbb's own vector and already excludes the script path.
;; Do NOT rebuild this from process.argv: the offsets differ between `nbb x.cljs`
;; and `npx --yes nbb x.cljs`, and getting it wrong makes the script read its own
;; path as <dir> and exit 3 -- which looks exactly like "the file is missing".
(def argv (vec *command-line-args*))
(def flags (set (filter #(str/starts-with? % "--") argv)))
(def offline? (contains? flags "--offline"))
(def root (or (first (remove #(str/starts-with? % "--") argv)) "."))

(def catalog-path (path/join root "data" "sanctions-sources.edn"))

(defn die-inconclusive [msg]
  (println (str "INCONCLUSIVE\t" msg))
  (println "Refusing to report a pass: the check did not run.")
  (.exit js/process 3))

(defn slurp* [p]
  (try (.readFileSync fs p "utf8") (catch :default _ nil)))

;; ── load ─────────────────────────────────────────────────────────────────────

(when-not (.existsSync fs catalog-path)
  (die-inconclusive (str "no catalog at " catalog-path)))

(def catalog
  (let [t (slurp* catalog-path)]
    (when-not t (die-inconclusive (str "could not read " catalog-path)))
    (try
      (edn/read-string t)
      (catch :default e
        (die-inconclusive (str "catalog is not readable EDN: " (.-message e)))))))

(when-not (map? catalog)
  (die-inconclusive "catalog did not read as a map"))

(def sources    (vec (:sources catalog)))
(def thresholds (vec (:thresholds catalog)))
(def statutes   (vec (:statutes catalog)))

;; Evidence floor. An empty catalog must not be reported as "0 broken citations".
(def cited
  (vec (concat (map (fn [s] {:id (:source/id s) :url (:source/url s)
                             :status (:source/http-status s) :kind :source}) sources)
               (map (fn [t] {:id (:threshold/id t) :url (:threshold/source-url t)
                             :status (:source/http-status t) :kind :threshold}) thresholds)
               (map (fn [t] {:id (keyword (str (name (:threshold/id t)) "-human"))
                             :url (:threshold/human-url t)
                             :status (:source/http-status t) :kind :threshold}) thresholds)
               (map (fn [s] {:id (:statute/id s) :url (:source/url s)
                             :status (:source/http-status s) :kind :statute}) statutes))))

(when (zero? (count cited))
  (die-inconclusive "catalog cites nothing -- an empty catalog is not a clean catalog"))

(when (some #(str/blank? (str (:url %))) cited)
  (die-inconclusive "a cited entry has no :source/url"))

;; ── leg 1: the tree agrees with the catalog ──────────────────────────────────
;;
;; Runs before the network leg so that a code/catalog disagreement is still
;; reported when egress is down.

(defn check-threshold [t]
  (let [p    (path/join root (:threshold/code-path t))
        sym  (:threshold/code-symbol t)
        want (:threshold/value t)]
    (cond
      (not (.existsSync fs p))
      {:id (:threshold/id t) :ok false
       :why (str ":threshold/code-path does not exist: " (:threshold/code-path t))}

      :else
      (let [txt (slurp* p)
            m   (when txt
                  (re-find (re-pattern (str sym "\\s*=\\s*(\\d+)")) txt))]
        (cond
          (nil? txt)
          {:id (:threshold/id t) :ok false :why (str "could not read " (:threshold/code-path t))}

          (nil? m)
          {:id (:threshold/id t) :ok false
           :why (str "symbol " sym " not found in " (:threshold/code-path t)
                     " -- the constant was renamed or removed without moving the citation")}

          :else
          (let [got (js/parseInt (second m) 10)]
            (if (= got want)
              {:id (:threshold/id t) :ok true :value got}
              {:id (:threshold/id t) :ok false
               :why (str sym " is " got " in " (:threshold/code-path t)
                         " but the catalog cites " want
                         " from " (:threshold/authority t))})))))))

(def tree-results (mapv check-threshold thresholds))
(def tree-failures (filterv #(not (:ok %)) tree-results))

;; ── leg 2: the citations still resolve ───────────────────────────────────────
;;
;; RANGE request: these documents run to 28 MB and this check is about liveness,
;; not content. 206 and 200 are the same answer to "is this citation alive"; 403,
;; 404 and 5xx are not, and are never normalised.

(defn probe! [{:keys [url]}]
  (-> (js/fetch url #js {:method "GET"
                         :redirect "follow"
                         :headers #js {"Range" "bytes=0-2047"
                                       "User-Agent" "cloud-itonami/app-screener verify-sanctions-sources"}})
      (.then (fn [r] {:url url :status (.-status r) :reached? true}))
      (.catch (fn [e] {:url url :status nil :reached? false :error (.-message e)}))))

(defn normalise [s] (if (= s 206) 200 s))

(defn run-network []
  (-> (js/Promise.all (clj->js (map probe! cited)))
      (.then
       (fn [rs]
         (let [rs (map (fn [c r] (merge c (js->clj r :keywordize-keys true))) cited rs)
               unreached (filterv #(not (:reached? %)) rs)
               ;; Egress, not the citations. If NOTHING was reachable, the run
               ;; cannot tell a dead network from dead citations, so it declines
               ;; to answer rather than reporting every source as broken.
               _ (when (= (count unreached) (count rs))
                   (die-inconclusive
                    (str "no citation was reachable (" (count rs) "/" (count rs)
                         ") -- this looks like egress, not the citations: "
                         (:error (first unreached)))))
               broken (filterv (fn [r]
                                 (or (not (:reached? r))
                                     (not= (normalise (:status r)) (:status (first (filter #(= (:url %) (:url r)) cited))))))
                               rs)]
           {:results rs :broken broken})))))

;; ── report ───────────────────────────────────────────────────────────────────

(defn report! [{:keys [results broken]}]
  (println (str "SCANNED\t" (count cited)))
  (println (str "sources=" (count sources)
                " thresholds=" (count thresholds)
                " statutes=" (count statutes)
                " citations=" (count cited)))
  (doseq [r tree-results]
    (println (str "  tree  " (if (:ok r) "OK  " "FAIL") "\t" (name (:id r))
                  (if (:ok r) (str "\t= " (:value r)) (str "\t" (:why r))))))
  (when results
    (doseq [r (sort-by #(str (:id %)) results)]
      (println (str "  cite  "
                    (if (some #(= (:url %) (:url r)) broken) "FAIL" "OK  ")
                    "\t" (name (:id r))
                    "\t" (if (:reached? r) (:status r) (str "unreachable: " (:error r)))))))
  (let [n-broken (count (or broken []))
        n-tree   (count tree-failures)]
    (cond
      (pos? (+ n-broken n-tree))
      (do (println (str "FAIL\t" n-tree " tree disagreement(s), " n-broken " broken citation(s)"))
          (.exit js/process 1))

      offline?
      (do (println "INCONCLUSIVE\t--offline: the tree agreed, but no citation was measured")
          (.exit js/process 3))

      :else
      (do (println "OK\tevery citation resolved and every cited constant matched the tree")
          (.exit js/process 0)))))

(if offline?
  (report! {:results nil :broken []})
  (-> (run-network)
      (.then report!)
      (.catch (fn [e] (die-inconclusive (str "the network leg threw: " (.-message e)))))))
