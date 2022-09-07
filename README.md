# Folklore

_It's a kind of saga._

In-memory implementation of the [saga pattern](https://www.baeldung.com/cs/saga-pattern-microservices) in Clojure.

**Status**: Pre-alpha. Tests pass, but this hasn't been battle tested yet.

## Background

This library was written to coordinate processes consisting of multiple steps,
some of which have side effects that need to be rolled back should any later
step fail.

A simplified example of processing an order in an online store system, is
modelled in [_Usage_](#usage) section below.

## Usage

Simple example:

```clojure
(require '[walterl.folklore :as fl])

(defn- create-order
  [{:keys [item-code quantity] :as ctx}]
  (assoc ctx
         ::order (store/create-order item-code quantity)
         ::status :order-accepted))

(defn- cancel-order
  [{:keys [::order] :as ctx}]
  (cond-> ctx
    (:id order) (assoc ::order (store/cancel-order (:id order)))))

(defn- process-payment
  [{:keys [account-num ::order] :as ctx}]
  (assoc ctx
         ::payment (account/debit account-num (:total order))
         ::status :paid))

(defn- reverse-payment
  [{:keys [::payment] :as ctx}]
  (cond-> ctx
    (:id payment) (assoc ::payment (account/reverse-payment (:id payment)))))

(defn- reserve-inventory
  [{:keys [item-code quantity] :as ctx}]
  (assoc ctx
         ::inventory (inventory/reserve item-code quantity)
         ::status :inventory-reserved))

(defn- release-inventory
  [{:keys [::inventory item-code] :as ctx}]
  (cond-> ctx
    (:qty-reserved inventory) (inventory/release item-code (:qty-reserved inventory))))

(defn- deliver-order
  [{:keys [account-num ::order] :as ctx}]
  (assoc ctx
         ::shipping (ship-order (:id order) account-num)
         ::status :shipping))

(def order {:item-code "gizmo1"
            :quantity 3
            :account-num "123"})

(def order-steps [{::fl/step create-order
                   ::fl/rollback cancel-order}
                  {::fl/step process-payment
                   ::fl/rollback reverse-payment}
                  {::fl/step reserve-inventory
                   ::fl/rollback release-inventory}
                  {::fl/step deliver-order}]) ; no rollback

(fl/reduce-steps order-steps order)

;; => {::order {...}
;;     ::payment {...}
;;     ::inventory {...}
;;     ::shipping {...}
;;     ::status ::shipping
;;     ;; walterl.folklore namespaced entries (see below)
;;     }
```

See [`test/walterl/folklore_test.clj`](./test/walterl/folklore_test.clj#L13=) for another, contrived example.

### API

Folklore consists of a single function: `(fl/reduce-steps steps ctx)`

Each step in `steps` is a map of the form:

```clojure
{::fl/step step-fn
 ::fl/rollback rollback-fn}
```

When `reduce-steps` is called, each step's `::fl/step` function is called, in
order, with the value returned by the previous step. `reduce-steps` returns the
value returned by the last step, with some progress values `assoc`ed in (see
below). The first step is called with initial map `ctx`, or an empty map.

If any `::fl/step` function throws an unhandled `java.lang.Exception`, the
corresponding (optional) `::fl/rollback` function of each step, up to and
including the throwing step, is called, in reverse order: the failing step's
rollback is called first, then the last successful step's, then the second last
successful step's, etc. Rollbacks are called with the return value of the last
rollback. The first rollback (the failing step's) is called with the return
value from the last successful step. When rolling back, `reduce-steps` returns
the return value of the last called rollback function.

These _compensating transaction_ functions must be [**idempotent** and **retryable**][1].

`ctx` must be map-like, because `reduce-steps` uses it to record saga progress
and error data. The following keys are be used:

* `::fl/succeeded`: Sequence of successfully completed steps.
* `::fl/failed`: The step that raised an unhandled `java.lang.Exception`.
* `::fl/error`: The exception that was raised by the failing step.
* `::fl/rollbacks`: Sequence of rolled back steps.
* `::fl/rollback-errors`: Unhandled exceptions raised in rollback functions.

[1]: https://www.baeldung.com/cs/saga-pattern-microservices#1-what-is-saga-architecture-pattern

### Notes

- Folklore doesn't include any customizable error handling or retry
  functionality. Use [diehard](https://github.com/sunng87/diehard) or similar for that.

- Although not required, adding identifiers in step maps (e.g. `::id
  ::create-order`) helps with inspection and debugging.

- If a rollback function raises an unhandled exception the error is appended to
  `:walterl.folklore/rollback-errors` as a map with keys `::fl/error` (the
  unhandled exception) and `::fl/step` (the top-level step map; the exception
  occurred in its `::fl/rollback` function). The next rollback is then called.

- Failed rollbacks are not retried.

- The failing step's rollback is called first. Since the unhandled exception
  that caused the rollback could have been raised at any point during the
  `::fl/step` function's execution, rollbacks should not assume that outputs
  from its corresponding `::fl/step` function is present in the input map.
  Similarly, side effects of `::fl/step` function may or may not have happened.

- Only exceptions of type `java.lang.Exception` or derived – notably **not**
  `java.lang.Throwable` – are handled.

- The same behavior can be modelled with Pedestal-like [interceptors][p-int]
  ([and][exo-int] [similar][siep]), but Folklore includes more of the machinery to keep track of
  executed steps and "direction of execution", as [Pedestal puts it][p-err].

[p-int]: http://pedestal.io/reference/interceptors
[p-err]: http://pedestal.io/reference/error-handling
[exo-int]: https://github.com/exoscale/interceptor
[siep]: https://github.com/metosin/sieppari

## License

Copyright © 2022 Walter

Distributed under the [Eclipse Public License version 1.0](./LICENSE).
