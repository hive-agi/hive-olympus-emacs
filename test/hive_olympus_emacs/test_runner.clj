(ns hive-olympus-emacs.test-runner
  (:require [clojure.test :as test]
            [hive-olympus-emacs.manifest-test]))

(defn -main
  [& _]
  (let [{:keys [fail error]} (test/run-tests 'hive-olympus-emacs.manifest-test)]
    (shutdown-agents)
    (System/exit (if (pos? (+ fail error)) 1 0))))
