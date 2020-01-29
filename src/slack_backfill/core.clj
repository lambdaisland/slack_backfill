(ns slack-backfill.core
  (:require [clj-slack.conversations]
            [clj-slack.users]
            [cheshire.core :as cheshire]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]))

(defn write-edn [data target filename]
  (let [filepath (str target "/" filename ".edn")]
    (.mkdirs (io/file target))
    (println (str "Fetching " filename " data"))
    (with-open [writer (io/writer filepath)]
      (binding [*print-length* false
                *out*          writer]
        (pprint/pprint data)))))

(defn user->tx [{:keys [id name is_admin is_owner profile]}]
  (let [{:keys [image_512 email real_name_normalized image_48 image_192
                real_name image_72 image_24 avatar_hash image_32 display_name
                display_name_normalized]} profile]
    (->> (merge #:user {:slack-id  id
                        :name      name
                        :real-name real_name
                        :admin?    is_admin
                        :owner?    is_owner}
                #:user-profile {:email                   email,
                                :avatar-hash             avatar_hash,
                                :image-32                image_32,
                                :image-24                image_24,
                                :image-192               image_192,
                                :image-48                image_48,
                                :real-name-normalized    real_name_normalized,
                                :display-name-normalized display_name_normalized,
                                :display-name            display_name,
                                :image-72                image_72,
                                :real-name               real_name,
                                :image-512               image_512})
         (remove (comp nil? val))
         (into {}))))

(defn channel->tx [{:keys [id name created creator]}]
  #:channel {:slack-id id
             :name     name
             :created  created
             :creator  [:user/slack-id creator]})

(defn fetch-users [connection]
  (let [fetch-batch (partial clj-slack.users/list connection)]
    (loop [batch (fetch-batch)
           result ()]
      (let [result (into result (:members batch))
            cursor (get-in batch [:response_metadata :next_cursor])]
        (if (empty? cursor)
          result
          (let [new-batch (fetch-batch {:cursor cursor})]
            (recur new-batch result)))))))

(defn fetch-channels [connection]
  (let [fetch-batch (partial clj-slack.conversations/list connection)]
    (loop [batch (fetch-batch)
           result ()]
      (let [result (into result (:channels batch))
            cursor (get-in batch [:response_metadata :next_cursor])]
        (if (empty? cursor)
          result
          (let [new-batch (fetch-batch {:cursor cursor})]
            (recur new-batch result)))))))

(defn fetch-channel-history [connection channel-id]
  (let [fetch-batch (partial clj-slack.conversations/history connection channel-id)]
    (loop [batch   (fetch-batch)
           history ()]
      (let [history (into history (:messages batch))
            cursor  (get-in batch [:response_metadata :next_cursor])]
        (if (empty? cursor)
          (map #(assoc % :channel channel-id) history)
          (let [new-batch (fetch-batch {:cursor cursor})]
            (recur new-batch history)))))))

(defn fetch-and-write-logs [target channels connection]
  (let [logs-target (str target "/logs")]
    (.mkdirs (io/file logs-target))
    (doseq [{channel-id   :id
             channel-name :name} channels]
      (println "Fetching" channel-id)
      (let [history (fetch-channel-history connection channel-id)]
        (with-open [file (io/writer (str logs-target "/000_" channel-id "_" channel-name ".txt"))]
          (doseq [message history]
            (cheshire/generate-stream message file)
            (.write file "\n")))))))

(defn -main
  "Takes a path like /tmp/test (without a trailing slash), fetches users and
  channels data into path root and channel logs into a /logs subdirectory."
  [target]
  (let [slack-token (System/getenv "SLACK_TOKEN")
        connection  {:api-url "https://slack.com/api" :token slack-token}
        users       (fetch-users connection)
        channels    (fetch-channels connection)]
    (-> (map user->tx users)
        (write-edn target "users"))
    (-> (map channel->tx channels)
        (write-edn target "channels"))
    (fetch-and-write-logs target channels connection)))

(comment
  (let [target "/tmp/test"]
    (.mkdirs (io/file target))
    (-main target)))
