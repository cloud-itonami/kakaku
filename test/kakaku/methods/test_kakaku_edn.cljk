(ns kakaku.methods.test-kakaku-edn
  "Tests for kakaku.methods.kakaku-edn (exact Python oracle outputs)."
  (:require [clojure.test :refer [deftest is]]
            [kakaku.methods.kakaku-edn :as k]))

(def seed
  "[{:product/id \"p1\" :product/name \"Widget\" :product/brand \"Acme\" :product/jan \"4900000000001\" :product/category \"tools\"}
    {:merchant/id \"m1\" :merchant/name \"ShopA\" :merchant/region :jp :merchant/reputation-score 87 :merchant/status :active}
    {:offer/id \"m1:o1\" :offer/price 1000 :offer/shipping-fee 200 :offer/total-price 1200 :offer/availability :in-stock :offer/delivery-eta-days 3 :offer/product-url \"http://a/o1\"}
    {:offer/id \"m2:o2\" :offer/price 950 :offer/total-price 950 :offer/availability :backorder}
    {:ph/total-price 1180 :ph/availability :in-stock :ph/observed-at \"2026-06-01\"}]")

(deftest reader-smoke
  (is (= [":a" 1 true nil "x"]
         (k/read-all "[:a 1 true nil \"x\"]"))))

(deftest classify-returns-oracle
  (let [parsed (k/read-all seed)
        result (k/classify parsed)]
    (is (= {"p1" {"productId" "p1" "name" "Widget" "brand" "Acme" "jan" "4900000000001" "category" "tools"}}
           (:products result)))
    (is (= {"m1" {"merchantId" "m1" "name" "ShopA" "region" "jp" "reputationScore" 87 "status" "active"}}
           (:merchants result)))
    (is (= [{"offerId" "m1:o1" "merchantId" "m1" "price" 1000 "shippingFee" 200 "totalPrice" 1200 "availability" "in-stock" "deliveryEtaDays" 3 "productUrl" "http://a/o1"}
            {"offerId" "m2:o2" "merchantId" "m2" "price" 950 "shippingFee" 0 "totalPrice" 950 "availability" "backorder" "deliveryEtaDays" 14 "productUrl" nil}]
           (:offers result)))
    (is (= [{"totalPrice" 1180 "availability" "in-stock" "observedAt" "2026-06-01"}]
           (:price-history result)))))
