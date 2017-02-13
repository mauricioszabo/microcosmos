(ns microscope.db
  (:require [clojure.string :as str]
            [clojure.java.jdbc :as jdbc]))

(defprotocol RelationalDatabase
  (execute-command! [db sql-command])
  (query-database [db sql-query])
  (get-jdbc-connection [db])
  (using-jdbc-connection [db conn]))

(def ^:dynamic mocked-db nil)
(defonce adapter-fns (atom {}))

(defn- connect-to-adapter [connection-params params]
  (let [adapter (:adapter connection-params)
        _ (require [(symbol (str "microscope.db." (name adapter)))])
        conn-factory (get @adapter-fns adapter)]
    (conn-factory connection-params params)))

(defn- mock-connection [connection params]
  (let [connect! (delay (connect-to-adapter {:adapter :sqlite :file ":memory:"} params))
        conn (swap! connection #(or % @connect!))]
    (alter-var-root #'mocked-db (constantly conn))
    conn))

(defn connect-to [ & {:as connection-params}]
  (let [connection (atom nil)]
    (fn [params]
      (if (:mocked params)
        (mock-connection connection params)
        (swap! connection #(or % (connect-to-adapter connection-params params)))))))

(defn- quote-regex [s]
  (-> s
      str
      clojure.string/re-quote-replacement
      (str/replace #"\." "\\\\.")
      (str/replace #"\-" "\\\\-")))

(defn normalize [sql params]
  (let [re (->> params
                keys
                (map quote-regex)
                sort
                reverse
                (str/join "|")
                re-pattern)
        matches (re-seq re sql)]
    (->> matches
         (map #(get params (keyword (str/replace-first % #":" ""))))
         (cons (str/replace sql re "?"))
         vec)))

(defn execute!
  ([db sql-command] (execute-command! db [sql-command]))
  ([db sql-command params] (execute-command! db (normalize sql-command params))))

(defn insert! [db table attributes]
  (let [keys (keys attributes)
        fields (map name keys)]
    (execute! db
              (str "INSERT INTO " table
                   "(" (str/join "," fields) ")"
                   " VALUES(" (str/join "," keys) ")")
              attributes)))

(defn update! [db table attributes where]
  (let [wheres (->> where
                    (map (fn [[k v]] [(-> k name (str "-where-clause-") keyword) v]))
                    (into {}))
        set-clause (->> attributes
                        (map (fn [[attr value]] (str (name attr) " = " attr)))
                        (str/join ","))
        where-clause (->> where
                          (map (fn [[attr value]] (str (name attr) " = " attr "-where-clause-")))
                          (str/join " AND "))]
    (execute! db (str "UPDATE " table " SET " set-clause " WHERE " where-clause)
              (merge attributes wheres))))

(defn query
  ([db sql-command] (query-database db [sql-command]))
  ([db sql-command params] (query-database db (normalize sql-command params))))

(defmacro transaction [db & body]
  `(jdbc/with-db-transaction [db# (get-jdbc-connection ~db)]
     (let [~db (using-jdbc-connection ~db db#)]
       ~@body)))

(defn rollback! [db]
  (execute! db "ROLLBACK")
  (execute! db "BEGIN"))

; I REALLY hope that in the future we decide for some library that writes
; these queries for us, but for now...
(defn select-for-update [db  & {:keys [select from where order binds]
                                :or {select "*"}}]
  (let [is-sqlite (-> db :conn :datasource .getJdbcUrl (str/starts-with? "jdbc:sqlite"))
        sql (cond-> (str "SELECT " select " FROM " from)
                    where (str " WHERE " where)
                    order (str " ORDER BY " order)
                    (not is-sqlite) (str " FOR UPDATE"))]
    (if binds
      (query db sql binds)
      (query db sql))))

(defn upsert! [db table key attributes]
  (transaction db
    (let [result (select-for-update db :select "1"
                                       :from table
                                       :where (str (name key) " = " key)
                                       :binds attributes)]
      (case (count result)
        0 (insert! db table attributes)
        1 (update! db table (dissoc attributes :id) (select-keys attributes [key]))
        (throw (ex-info "Multiple results - expected one or zero" {:count (count result)}))))))

(defn fake-rows
  "Generates an in-memory database, prepared by `prepare-fn`, and with some rows
already populated. The database will be created, populated by `tables-and-rows`, and
then returned as a connection ready to make modifications.

Usage example:

(let [db (fake-rows #(db/execute! \"CREATE TABLE example (name VARCHAR(255))\"))
                    {:example [{:name \"foo\"} {:name \"bar\"}]}]
  (db/query \"SELECT * FROM example\"))"
  [prepare-fn tables-and-rows]
  (let [conn-factory (connect-to :adapter :sqlite3)
        db (conn-factory {:mocked true :setup-db-fn prepare-fn})]
    (doseq [[table rows] tables-and-rows
            row rows]
      (insert! db (name table) row))
    db))