# Folklore

_It's a kind of saga._

An implementation of the [saga pattern](https://www.baeldung.com/cs/saga-pattern-microservices) in Clojure.

## Usage

Simple example:

```clojure
(require '[walterl.folklore :refer [reduce-steps]])

(defn- create-order
  [{:keys [item-code quantity] :as m}]
  (assoc m
         ::order (store/create-order item-code quantity)
         ::status :order-accepted))

(defn- cancel-order
  [{:keys [::order] :as m}]
  (cond-> m
    (:id order) (assoc ::order (store/cancel-order (:id order)))))

(defn- process-payment
  [{:keys [account-num ::order] :as m}]
  (assoc m
         ::payment (account/debit account-num (:total order))
         ::status :paid))

(defn- reverse-payment
  [{:keys [::payment] :as m}]
  (cond-> m
    (:id payment) (assoc ::payment (account/reverse-payment (:id payment)))))

(defn- reserve-inventory
  [{:keys [item-code quantity] :as m}]
  (assoc m
         ::inventory (inventory/reserve item-code quantity)
         ::status :inventory-reserved))

(defn- release-inventory
  [{:keys [::inventory item-code] :as m}]
  (cond-> m
    (:qty-reserved inventory) (inventory/release item-code (:qty-reserved inventory))))

(defn- deliver-order
  [{:keys [account-num ::order] :as m}]
  (assoc m
         ::shipping (ship-order (:id order) account-num)
         ::status :shipping))

(def order {:item-code "gizmo1"
            :quantity 3
            :account-num "123"})

(def order-steps [{:step create-order
                   :rollback cancel-order}
                  {:step process-payment
                   :rollback reverse-payment}
                  {:step reserve-inventory
                   :rollback release-inventory}
                  {:step deliver-order}]) ; no rollback

(reduce-steps order-steps order)

;; => {::order {...}
;;     ::payment {...}
;;     ::inventory {...}
;;     ::shipping {...}
;;     ::status ::shipping
;;     ;; walterl.folklore namespaced entries (see below)
;;     }
```

See [`test/walterl/folklore_test.clj`](./test/walterl/folklore_test.clj#L13=) for another, contrived example.

### Steps

Each step in the saga is represented by a map of the form:

```clojure
{:step step-fn
 :rollback rollback-fn}
```

Steps' `:step` functions are called in order, with the value returned by the
previous step. `reduce-steps` returns the value returned by the last step, with
some progress values `assoc`ed in (see below). The first step is called with
initial map `m`, or an empty map.

If any `:step` function raises an unhandled exception, the corresponding
(optional) `:rollback` function of each step up to and including the failing
step is called, in reverse order: the failing step's rollback is called first,
then the last successful step's, then the second last successful step's, etc.
Rollbacks are called with the return value of the last rollback. The first
rollback (the failing step's) is called with the return value from the last
successful step. When rolling back, `reduce-steps` returns the return value of
the last called rollback function.

These _compensating transaction_ functions must be [**idempotent** and **retryable**][1].

`m` must be map-like, because `reduce-steps` uses it for recording saga
progress and error handling. The following keys are be used:

* `:walterl.folklore/succeeded`: Sequence of successfully completed steps.
* `:walterl.folklore/failed`: The step that raised an unhandled `java.lang.Exception`.
* `:walterl.folklore/error`: The exception that was raised by the failing step.
* `:walterl.folklore/rollbacks`: Sequence of rolled back steps.
* `:walterl.folklore/rollback-errors`: Unhandled exceptions raised in rollback functions.

[1]: https://www.baeldung.com/cs/saga-pattern-microservices#1-what-is-saga-architecture-pattern

### Notes

Folklore doesn't include any customizable error handling or retry
functionality. Use [diehard](https://github.com/sunng87/diehard) or similar for that.

Although not required, adding identifiers in step maps (e.g.
`::id ::create-order`) helps with inspection and debugging.

If a rollback function raises an unhandled exception the error is
appended to `:walterl.folklore/rollback-errors` as a map with keys `:error`
(the unhandled exception) and `step` (the top-level step map; the exception
occurred in its `:rollback` function). The next rollback is then called.

Failed rollbacks are not retried.

The failing step's rollback is called first. Since the unhandled exception that
caused the rollback could have been raised at any point during the `:step`
function's execution, rollbacks should not assume that outputs from its
corresponding `:step` function is present in the input map. Similarly, side
effects of `:step` function may or may not have happened.

Only exceptions of type `java.lang.Exception` or derived – notably **not**
`java.lang.Throwable` – are handled.

## Ops
Run the project's tests:

    $ clojure -T:build test

Run the project's CI pipeline and build a JAR (this will fail until you edit the tests to pass):

    $ clojure -T:build ci

This will produce an updated `pom.xml` file with synchronized dependencies inside the `META-INF`
directory inside `target/classes` and the JAR in `target`. You can update the version (and SCM tag)
information in generated `pom.xml` by updating `build.clj`.

Install it locally (requires the `ci` task be run first):

    $ clojure -T:build install


Copyright © 2022 Walter

Distributed under the Eclipse Public License version 1.0.
