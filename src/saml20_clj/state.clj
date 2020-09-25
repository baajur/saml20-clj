(ns saml20-clj.state
  (:require [java-time :as t]
            [pretty.core :refer [PrettyPrintable]]))

(defprotocol StateManager
  "Protocol for managing state for recording which requests are in flight, so we can determine whether responses
  correspond to valid requests. This library ships with a simple in-memory implementation, but this interface is
  provided so that you can provide your own implementation if you need to do something more sophisticated (such as
  synchronizing across multiple instances)."
  (record-request! [this request-id]
    "Called whenever a new request to the Dip goes out. The state manager should record `request-id` (and probably the
    current timestamp as well) so it can be used for validating responses.")

  ;; TODO -- consider renaming this to handle-response! or something else clearer
  (accept-response! [this request-id]
    "Called whenever a new response from IdP is received. The state manager should verify that `request-id` was
 actually issued by us (e.g., one we've seen earlier when `record-request!`), and (hopefully) that it is not too old;
 if the response is not acceptable, it must throw an Exception. The state manager should remove the request from its
 state a response with the same ID cannot be used again (e.g. to prevent replay attacks)."))

;; in-memory-state-manager state works like this:
;;
;; - state is a list with two buckets.
;; 1. Requests less than timeout/2 seconds old
;; 2. Requests older than timeout/2 seconds, but less than timeout seconds old
;;
;; Then every timeout/2 seconds we will drop bucket 2, bucket 1 will become the new bucket 2, and we will create a new
;; bucket 1.
;;
;; e.g. if timeout is five minutes (300 seconds) then we will rotate buckets every 150 seconds.
;;
;; buckets look like: [bucket-created-instant #{request-id}]

(defn- prune-buckets [state request-timeout-seconds]
  (let [now                                                                      (t/instant)
        [[bucket-1-created bucket-1-request-ids :as bucket-1] bucket-2 bucket-3] state]
    (letfn [(new-bucket []
              [now #{}])]
      (cond
        ;; state not initialized yet.
        (not bucket-1)
        [(new-bucket)]

        ;; all buckets are too old
        (t/before? bucket-1-created (t/minus now (t/seconds request-timeout-seconds)))
        [(new-bucket)]

        ;; bucket 1 is past the threshold and it's time to rotate the buckets
        (t/before? bucket-1-created (t/minus now (t/seconds (int (/ request-timeout-seconds 2)))))
        [(new-bucket) bucket-1 bucket-2]

        ;; not time to rotate the buckets yet
        :else
        state))))

(defn- in-memory-state-manager-record-request [state request-timeout-seconds request-id]
  (let [state (prune-buckets state request-timeout-seconds)]
    (update-in state [0 1] conj request-id)))

(defn- in-memory-state-manager-accept-response [state request-timeout-seconds request-id]
  (let [state (prune-buckets state request-timeout-seconds)]
    (or (some (fn [bucket-index]
                (when (contains? (get-in state [bucket-index 1]) request-id)
                  (update-in state [bucket-index 1] disj request-id)))
              [0 1 2])
        (throw (ex-info "Invalid request ID" {:request-id request-id})))))

;; 5 minutes, in case people decide they want to sit around on the IdP page for a bit.
(def default-request-timeout-seconds 300)

(defn in-memory-state-manager
  "A simple in-memory state manager, suitable for a single instance."
  ([]
   (in-memory-state-manager default-request-timeout-seconds))

  ([request-timeout-seconds]
   (in-memory-state-manager request-timeout-seconds []))

  ([request-timeout-seconds initial-state]
   (let [state (atom initial-state)]
     (reify
       PrettyPrintable
       (pretty [_]
         (list `in-memory-state-manager request-timeout-seconds @state))

       StateManager
       (record-request! [_ request-id]
         (swap! state in-memory-state-manager-record-request request-timeout-seconds request-id))
       (accept-response! [_ request-id]
         (swap! state in-memory-state-manager-accept-response request-timeout-seconds request-id))

       ;; this is here mostly for convenience and testability: deref the state manager itself to see what's in the
       ;; state atom
       clojure.lang.IDeref
       (deref [_]
         @state)))))
