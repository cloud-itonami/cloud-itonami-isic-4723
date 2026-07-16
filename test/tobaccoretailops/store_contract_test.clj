(ns tobaccoretailops.store-contract-test
  "Contract tests for `tobaccoretailops.store/Store` protocol."
  (:require [clojure.test :refer [deftest is testing]]
            [tobaccoretailops.store :as store]))

(deftest mem-store-store-lookup
  (testing "MemStore can store and retrieve stores by ID (string keys)"
    (let [stores {"s1" {:store-id "s1" :name "Alice's Tobacconist" :registered? true :licensed? true}}
          s (store/mem-store stores)]
      (is (some? (store/store-record s "s1")))
      (is (nil? (store/store-record s "s99"))))))

(deftest mem-store-all-stores
  (testing "MemStore returns all stores in sorted order"
    (let [stores {"s2" {:store-id "s2" :name "Bob's Cigar Lounge"}
                  "s1" {:store-id "s1" :name "Alice's Tobacconist"}
                  "s3" {:store-id "s3" :name "Carol's Vape Specialty"}}
          s (store/mem-store stores)
          all-s (store/all-stores s)]
      (is (= 3 (count all-s)))
      (is (= "s1" (:store-id (first all-s))))
      (is (= "s3" (:store-id (last all-s)))))))

(deftest mem-store-ledger-append
  (testing "MemStore append-ledger! adds facts to immutable log"
    (let [s (store/mem-store {})
          fact1 {:t :test :data "fact1"}
          fact2 {:t :test :data "fact2"}]
      (is (= 0 (count (store/ledger s))))
      (store/append-ledger! s fact1)
      (is (= 1 (count (store/ledger s))))
      (store/append-ledger! s fact2)
      (is (= 2 (count (store/ledger s)))))))

(deftest mem-store-coordination-log
  (testing "MemStore commit-record! appends to coordination-log"
    (let [s (store/mem-store {})
          record {:op :log-sales-record :store-id "s1" :value {:units-sold 100}}]
      (is (= 0 (count (store/coordination-log s))))
      (store/commit-record! s record)
      (is (= 1 (count (store/coordination-log s))))
      (is (= record (first (store/coordination-log s)))))))

(deftest mem-store-with-stores
  (testing "MemStore with-stores replaces the store directory"
    (let [s (store/mem-store {})
          new-stores {"s1" {:store-id "s1" :name "Alice's Tobacconist"}}]
      (is (= 0 (count (store/all-stores s))))
      (store/with-stores s new-stores)
      (is (= 1 (count (store/all-stores s)))))))

(deftest seed-db-has-demo-data
  (testing "seed-db creates a populated MemStore with demo stores"
    (let [s (store/seed-db)]
      (is (> (count (store/all-stores s)) 0))
      (is (some? (store/store-record s "store-1")))
      (is (some? (store/store-record s "store-2")))
      (is (some? (store/store-record s "store-3"))))))

(deftest demo-data-string-key-consistency
  (testing "demo-data uses string keys, not keywords, for store-id"
    (let [demo (store/demo-data)
          stores (:stores demo)]
      (doseq [[k v] stores]
        (is (string? k) "keys must be strings")
        (is (string? (:store-id v)) "store-id must be string")
        (is (= k (:store-id v)) "key must match store-id")))))

(deftest store-is-append-only
  (testing "appended facts are immutable and never removed"
    (let [s (store/seed-db)
          fact1 {:t :event1 :data "a"}
          fact2 {:t :event2 :data "b"}]
      (store/append-ledger! s fact1)
      (let [ledger-after-1 (store/ledger s)]
        (store/append-ledger! s fact2)
        (let [ledger-after-2 (store/ledger s)]
          (is (= (count ledger-after-1) (dec (count ledger-after-2))))
          (is (every? #(some (fn [x] (= x %)) ledger-after-2) ledger-after-1)
              "all prior facts must still be present"))))))
