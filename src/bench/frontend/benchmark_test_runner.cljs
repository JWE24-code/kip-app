(ns frontend.benchmark-test-runner
  "Runs a benchmark"
  (:require [clojure.edn :as edn]
            [frontend.macros :refer [slurped]]
            [frontend.modules.file.uprint :as up]
            [clojure.pprint :as pprint]
            [clojure.test :refer [deftest testing]]
            [fipp.edn :as fipp]))

(def onboarding
  ;; representative edn document used by the pprint benchmarks
  (edn/read-string
   "{:pages ({:block/format :markdown, :block/original-name \"Foo\",
              :block/properties {:title \"my page\"}}),
     :blocks ({:block/content \"foo content a\", :block/format :markdown},
              {:block/content \"foo content b\", :block/format :markdown})}"))

(deftest test-pp-str
  (testing "pp-str benchmark"
    (simple-benchmark []
                      (with-out-str (pprint/pprint onboarding))
                      10)
    (simple-benchmark []
                      (with-out-str (fipp/pprint onboarding))
                      10)
    (simple-benchmark []
                      (up/ugly-pr-str onboarding)
                      10)
    (simple-benchmark []
                      (pr-str onboarding)
                      10)
    ;; uncomment to see the output
    #_(println (up/ugly-pr-str onboarding))))
