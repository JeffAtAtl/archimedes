(ns archimedes.core
  (:import (com.tinkerpop.blueprints Element TransactionalGraph TransactionalGraph$Conclusion)
           (com.tinkerpop.blueprints.impls.tg TinkerGraphFactory)))

(def ^{:dynamic true} *graph*)
(def ^{:dynamic true} *pre-fn* (fn []))
(def ^{:dynamic true} *post-fn* identity)

(defn set-graph! [g]
  (alter-var-root (var *graph*) (constantly g)))

(defn use-new-tinkergraph! []
  (set-graph! (TinkerGraphFactory/createTinkerGraph)))

(defn use-clean-graph! []
  (use-new-tinkergraph!)
  (doseq [e (seq (.getEdges *graph*))]
    (.removeEdge *graph* e))
  (doseq [v (seq (.getVertices *graph*))]
    (.removeVertex *graph* v))
  nil)

(defn shutdown 
  "Shutdown the graph."
  [] (alter-var-root (var *graph*) (fn [m] (when m (.shutdown m)))))

(defn get-features
  "Get a map of features for a graph.
  (http://tinkerpop.com/docs/javadocs/blueprints/2.1.0/com/tinkerpop/blueprints/Features.html)"
  []
  (-> *graph* .getFeatures .toMap))

(defn get-feature
  "Gets the value of the feature for a graph."
  [f]
  (.get (get-features) f))

(defn- transact!* [f]
  (if (get-feature "supportsTransactions")
    (try
      (let [tx      (.startTransaction *graph*)
            results (binding [*graph* tx] (f))]
        (.commit tx)
        (.stopTransaction *graph* TransactionalGraph$Conclusion/SUCCESS)
        results)
      (catch Exception e
        (.stopTransaction *graph* TransactionalGraph$Conclusion/FAILURE)
        (throw e)))
    ; Transactions not supported.
    (f)))

(defmacro transact! [& forms]
  "Perform graph operations inside a transaction."
  `(~transact!* (fn [] ~@forms)))

(defn- retry-transact!* [max-retries wait-time-fn try-count f]
  (let [res (try {:value (transact!* f)}
              (catch Exception e
                {:exception e}))]
    (if-not (:exception res)
      (:value res)
      (if (> try-count max-retries)
        (throw (:exception res))
        (let [wait-time (wait-time-fn try-count)]
          (Thread/sleep wait-time)
          (recur max-retries wait-time-fn (inc try-count) f))))))

(defmacro retry-transact! [max-retries wait-time & forms]
  "Perform graph operations inside a transaction.  The transaction will retry up
  to `max-retries` times.  `wait-time` can be an integer corresponding to the
  number of milliseconds to wait before each try, or it can be a function that
  takes the retry number (starting with 1) and returns the number of
  milliseconds to wait before that retry."
  `(let [wait-time-fn# (if (ifn? ~wait-time)
                           ~wait-time
                           (constantly ~wait-time))]
    (~retry-transact!* ~max-retries wait-time-fn# 1 (fn [] ~@forms))))

(defmacro with-graph
  "Perform graph operations against a specific graph."
  [g & forms]
  `(binding [*graph* ~g]
     ~@forms))
