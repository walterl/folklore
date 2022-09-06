(ns walterl.folklore-test
  (:require [clojure.test :refer [deftest is testing]]
            [walterl.folklore :as fl]))

(defn- conj-step
  [step-id]
  (fn [m] (update m :steps conj step-id)))

(defn- disj-step
  [step-id]
  (fn [m] (update m :steps disj step-id)))

(deftest reduce-steps-test
  (let [steps [{::id ::create-order ; id's aren't required, but helps with debugging
                :step #(assoc % :steps #{::create-order})
                :rollback (disj-step ::create-order)}
               {::id ::process-payment
                :step (conj-step ::process-payment)
                :rollback (disj-step ::process-payment)}
               {::id ::update-inventory
                :step (conj-step ::update-inventory)
                :rollback (disj-step ::update-inventory)}
               {::id ::deliver-order
                :step (conj-step ::deliver-order)
                :rollback (disj-step ::deliver-order)}]]
    (testing "basic success case"
      (let [result (fl/reduce-steps steps)]
        (is (= #{::create-order ::process-payment ::update-inventory
                 ::deliver-order}
               (:steps result)))
        (is (= steps
               (::fl/succeeded result)))))
    (testing "basic rollback"
      (let [test-error-msg "Test error"
            steps (assoc-in steps [2 :step]
                            #(throw (ex-info test-error-msg {:data %})))
            {:keys [::fl/error ::fl/failed ::fl/rollbacks] :as result}
            (fl/reduce-steps steps)]
        (is (= test-error-msg
               (ex-message error)))
        (is (empty? (:steps result))) ; rollbacks have disjoined all values
        (is (= #{::create-order ::process-payment} ; :steps at the time of error
               (-> error ex-data :data :steps)))
        (is (= ::update-inventory
               (::id failed)))
        ;; notice reverse order below, and includes failing step ::update-inventory
        (is (= [::update-inventory ::process-payment ::create-order]
               (mapv ::id rollbacks)))
        (testing "with failing rollback"
          (let [failed-rb-msg "Test rollback failure"
                steps (assoc-in steps [1 :rollback]
                                #(throw (ex-info failed-rb-msg {:data %})))

                {:keys [::fl/error ::fl/failed ::fl/rollbacks] :as result}
                (fl/reduce-steps steps)

                rb-err (-> result ::fl/rollback-errors first)]
            (is (= failed-rb-msg
                   (ex-message (:error rb-err))))
            (is (= (get steps 1)
                   (:step rb-err)))
            ;; the rest is similar as for non-failing rollbacks, except the
            ;; failing rollback's disj isn't performed (because it "failed" :P)
            (is (= test-error-msg
                   (ex-message error)))
            (is (= #{::create-order ::process-payment} ; :steps at the time of error
                   (-> error ex-data :data :steps)))
            (is (= ::update-inventory
                   (::id failed)))
            ;; notice reverse order below, and includes failing step ::update-inventory
            (is (= [::update-inventory ::create-order]
                   (mapv ::id rollbacks)))))))
    (testing "rollback functions are optional"
      (let [steps (-> steps
                      (update 1 dissoc :rollback) ; ::process-payment step
                      (assoc-in [2 :step] #(throw (ex-info "Test error" {}))))
            result (try
                     (fl/reduce-steps steps)
                     (catch Exception _
                       ::failed))]
        (testing "doesn't throw due to missing :rollback"
          (is (not= ::failed
                    result)))
        (is (= #{::process-payment} ; no rollback, so couldn't disj step value
               (:steps result)))))))
