(defproject microcosmos "0.2.1"
  :description "Microservice architecture for Clojure"
  :url "https://github.com/mauricioszabo/microcosmos"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.clojure/clojurescript "1.9.908"]
                 [cheshire "5.6.1"]
                 [finagle-clojure/core "0.7.0"]
                 [finagle-clojure/http "0.7.0"]
                 [environ "1.1.0"]]

  :plugins [[lein-cljsbuild "1.1.7"]]

  :profiles {:dev {:src-paths ["dev"]
                   :dependencies [[midje "1.9.9"]
                                  [check "0.1.0-SNAPSHOT"]
                                  [figwheel-sidecar "0.5.13"]
                                  [com.cemerick/piggieback "0.2.1"]]
                   ; :repl-options {:nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}
                   :plugins [[lein-midje "3.2.1"]]}}

  :cljsbuild {:builds [{:source-paths ["src"]
                        :id "prod"
                        :compiler {:output-to "target/main.js"
                                   :optimizations :simple
                                   :hashbang false
                                   :output-wrapper true
                                   :pretty-print true
                                   :language-in :ecmascript5
                                   :target :nodejs}}
                       {:source-paths ["src" "test"]
                        :id "test"
                        :compiler {:output-to "target/test.js"
                                   :optimizations :simple
                                   :hashbang false
                                   :language-in :ecmascript5
                                   :output-wrapper true
                                   :output-dir "target/js-test"
                                   :pretty-print true
                                   :process-shim true
                                   :target :nodejs}}
                       {:source-paths ["src" "test"]
                        :id "dev"
                        :figwheel true
                        :compiler {:output-to "target/dev.js"
                                   :output-dir "target/js"
                                   :main microcosmos.all-tests
                                   :process-shim true
                                   :optimizations :none
                                   :target :nodejs}}]})
