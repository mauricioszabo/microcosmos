(ns microscope.relational-db
  (:require [microscope.healthcheck :as health]
            [clojure.java.jdbc :as jdbc]
            [clojure.string :as str])
  (:import [com.mchange.v2.c3p0 ComboPooledDataSource]))

(defn- check-select [db sql]
  (let [[result] (try (jdbc/query db sql)
                   (catch java.sql.SQLException _ [])
                   (catch Exception e [e]))]
    (case result
      {:ok "ok"} nil
      nil {:connection "failed simple select"}
      {:connection "unknown error"
       :exception-type (.getName (.getClass result))
       :exception-msg (.getMessage result)})))

(defrecord Database [datasource]
  health/Healthcheck
  (unhealthy? [self] (check-select self "SELECT 'ok' as ok")))

(defrecord HSQLDB [datasource]
  health/Healthcheck
  (unhealthy? [self] (check-select self "SELECT 'ok' as ok FROM (VALUES(0))")))

(defn pool-for [driver url username password]
  (doto (ComboPooledDataSource.)
        (.setDriverClass driver)
        (.setJdbcUrl url)
        (.setUser username)
        (.setPassword password)
        (.setMaxIdleTimeExcessConnections (* 30 60))
        (.setMaxIdleTime (* 3 60 60))))

(defn db-for [driver url username password]
  (->Database (pool-for driver url username password)))

(defn sqlite-memory [setup-db-fn]
  (let [db (db-for "org.sqlite.JDBC" "jdbc:sqlite::memory:" nil nil)
        pool (doto (:datasource db)
                   (.setMaxPoolSize 1)
                   (.setMinPoolSize 1)
                   (.setInitialPoolSize 1))]
    (when setup-db-fn (setup-db-fn db))
    db))

(defn hsqldb-memory [setup-db-fn]
  (let [pool (pool-for "org.hsqldb.jdbc.JDBCDriver"
                       (str "jdbc:hsqldb:mem:" (rand) ";shutdown=true")
                       "SA" "")
        db (->HSQLDB pool)]
    (when setup-db-fn (setup-db-fn db))
    db))

(defmacro gen-constructor [code]
  `(let [pool# (delay ~code)]
     (fn [params#]
       (if (:mocked params#)
         (sqlite-memory (:setup-db-fn params#))
         @pool#))))

(defn insert! [db table attributes]
  (let [keys (keys attributes)
        fields (map name keys)]
    (jdbc/execute! db
                   (cons
                    (str "INSERT INTO " table
                         "(" (str/join "," fields) ")"
                         " VALUES(" (str/join "," keys) ")")
                    (vals attributes)))))

(defn fake-rows
  "Generates an in-memory database, prepared by `prepare-fn`, and with some rows
already populated. The database will be created, populated by `tables-and-rows`, and
then returned as a connection ready to make modifications.

Usage example:

(let [db (fake-rows #(jdbc/execute! \"CREATE TABLE example (name VARCHAR(255))\"))
                    {:example [{:name \"foo\"} {:name \"bar\"}]}]
  (jdbc/query \"SELECT * FROM example\"))"
  [prepare-fn tables-and-rows]
  (let [db (sqlite-memory prepare-fn)]
    (doseq [[table rows] tables-and-rows
            row rows]
      (insert! db (name table) row))
    db))
