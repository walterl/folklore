(ns walterl.folklore
  (:require [clojure.spec.alpha :as s]))

(defn- append
  "Appends `x` to `vect`or or empty vector when nil.

  This is `conj` but creates a vector for nil `coll`."
  [vect x]
  (conj (or vect []) x))

(s/def ::step fn?)
(s/def ::rollback fn?)
(s/def ::step-info (s/keys :req [::step], :opt [::rollback]))
(s/def ::steps (s/coll-of ::step-info))

(s/def ::succeeded (s/coll-of ::step-info))
(s/def ::failed ::step-info)
(s/def ::error #(instance? Exception))
(s/def ::rollbacks (s/coll-of ::step-info))
(s/def ::rollback-error (s/keys :req [::error ::step]))
(s/def ::rollback-errors (s/coll-of ::rollback-error))
(s/def ::ctx (s/keys :opt [::succeeded ::failed ::error ::rollbacks ::rollback-errors]))

(defn- apply-rollback
  [ctx {:keys [::rollback] :as step-info}]
  (if (fn? rollback)
    (try
      (-> (rollback ctx)
          (update ::rollbacks append step-info))
      (catch Exception ex
        (update ctx ::rollback-errors append {::error ex, ::step step-info})))
    ctx ; should never happen, because rollback-steps filters for fns
    ))

(s/fdef apply-rollback
  :args (s/and (s/cat :ctx ::ctx, :step-info ::step-info))
  :ret ::ctx)

(defn- rollback-steps
  [{:keys [::succeeded ::failed] :as ctx}]
  (let [rollbacks (->> (into [failed] (reverse succeeded))
                       (filter (comp fn? ::rollback)))]
    (reduce apply-rollback ctx rollbacks)))

(s/fdef rollback-steps
  :args (s/cat :ctx ::ctx)
  :ret ::ctx)

(defn- apply-step
  [ctx {:keys [::step] :as step-info}]
  (try
    (-> (step ctx)
        (update ::succeeded append step-info))
    (catch Exception ex
      (reduced (assoc ctx
                      ::error ex
                      ::failed step-info)))))

(s/fdef apply-step
  :args (s/and (s/cat :ctx ::ctx, :step-info ::step-info))
  :ret (s/and ::ctx
              (s/or :success (s/keys :req [::succeeded])
                    :error (s/keys :req [::error ::failed]))))

(defn reduce-steps
  "Reduce over `steps` with optionally specified initial context map `ctx`.

  `steps` is a sequence of maps, each with a `::step` key, and an optional
  `::rollback` key. The values for both must be a function accepting a single
  argument – a context map returned by the preceding step – and returns a map,
  which will be passed to the next step. The first `::step` function will be
  called with `ctx`.

  `ctx` defaults to an empty map.

  If a step throws, the `::rollback` function for each step, up to and
  including the throwing step, is called, in reverse order. These _compensating
  transaction_ functions must be [**idempotent** and **retryable**][1].

  The following keys will be used for recording saga progress and error handling:

  * `::succeeded`: Sequence of successfully completed steps.
  * `::failed`: The step that raised an unhandled `java.lang.Exception`.
  * `::error`: The exception that was raised by the failing step.
  * `::rollbacks`: Sequence of rolled back steps.
  * `::rollback-errors`: Unhandled exceptions raised in rollback functions.

  [1]: https://www.baeldung.com/cs/saga-pattern-microservices#1-what-is-saga-architecture-pattern"
  ([steps]
   (reduce-steps steps {}))
  ([steps ctx]
   (let [{:keys [::error] :as ctx*} (reduce apply-step ctx steps)]
     (cond-> ctx*
       (some? error) (rollback-steps)))))

(s/fdef reduce-steps
  :args (s/or :without-ctx (s/cat :steps ::steps)
              :with-ctx    (s/cat :steps ::steps, :ctx ::ctx))
  :ret ::ctx)
