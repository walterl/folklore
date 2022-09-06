(ns walterl.folklore)

(defn- append
  "Appends `x` to `vect`or or empty vector when nil.
  
  This is `conj` but creates a vector for nil `coll`."
  [vect x]
  (conj (or vect []) x))

(defn- apply-rollback
  [acc {:keys [::rollback] :as step-info}]
  (if-not (fn? rollback)
    acc ; should never happen
    (try
      (-> (rollback acc)
          (update ::rollbacks append step-info))
      (catch Exception ex
        (update acc ::rollback-errors append {::error ex, ::step step-info})))))

(defn- rollback-steps
  [{:keys [::succeeded ::failed] :as result}]
  (let [rollbacks (->> (into [failed] (reverse succeeded))
                       (filter (comp fn? ::rollback)))]
    (reduce apply-rollback result rollbacks)))

(defn- apply-step
  [acc {:keys [::step] :as step-info}]
  (try
    (-> (step acc)
        (update ::succeeded append step-info))
    (catch Exception ex
      (reduced (assoc acc
                      ::error ex
                      ::failed step-info)))))

(defn reduce-steps
  "Reduce over `steps` with optionally specified initial context map `ctx`.

  `steps` should be a sequence of maps, each with a `:step` key, and an
  optional `:rollback` key. The values for both must be a function accepting a
  single argument – a map returned by the preceding step – and returns a map –
  that will be passed to the next step. The first `:step` function will be
  called with `ctx`.
  
  `ctx` defaults to an empty map.

  If a step fails (throws an error), all `:rollback` functions for steps up to
  and including the failing are called. These _compensating transaction_
  functions must be [**idempotent** and **retryable**][1].

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
