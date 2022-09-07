(ns walterl.folklore)

(defn- append
  "Appends `x` to `vect`or or empty vector when nil.
  
  This is `conj` but creates a vector for nil `coll`."
  [vect x]
  (conj (or vect []) x))

(defn- apply-rollback
  [ctx {:keys [::rollback] :as step-info}]
  (if-not (fn? rollback)
    ctx ; should never happen
    (try
      (-> (rollback ctx)
          (update ::rollbacks append step-info))
      (catch Exception ex
        (update ctx ::rollback-errors append {::error ex, ::step step-info})))))

(defn- rollback-steps
  [{:keys [::succeeded ::failed] :as result}]
  (let [rollbacks (->> (into [failed] (reverse succeeded))
                       (filter (comp fn? ::rollback)))]
    (reduce apply-rollback result rollbacks)))

(defn- apply-step
  [ctx {:keys [::step] :as step-info}]
  (try
    (-> (step ctx)
        (update ::succeeded append step-info))
    (catch Exception ex
      (reduced (assoc ctx
                      ::error ex
                      ::failed step-info)))))

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

  * `:walterl.folklore/succeeded`: Sequence of successfully completed steps.
  * `:walterl.folklore/failed`: The step that raised an unhandled `java.lang.Exception`.
  * `:walterl.folklore/error`: The exception that was raised by the failing step.
  * `:walterl.folklore/rollbacks`: Sequence of rolled back steps.
  * `:walterl.folklore/rollback-errors`: Unhandled exceptions raised in rollback functions.

  [1]: https://www.baeldung.com/cs/saga-pattern-microservices#1-what-is-saga-architecture-pattern"
  ([steps]
   (reduce-steps steps {}))
  ([steps ctx]
   (let [{:keys [::error] :as result} (reduce apply-step ctx steps)]
     (cond-> result
       (some? error) (rollback-steps)))))
