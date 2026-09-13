(ns hive-olympus-emacs.probe
  "Real-Emacs probe for the brick. Starts a PRIVATE Emacs daemon on its own
   socket (never the user's session), mounts the real hive.olympus core over
   a stub roster plus the shipped brick manifest, with the eval fn bound to
   that daemon, then reads the panel buffers back out of the daemon.

   REPL:  (def d (start-daemon! \"olympus-probe\"))
          (run-probe! 6)
          (stop-daemon! d)
   Cold:  clojure -Sdeps \"$(cat local.deps.edn)\" -M:probe"
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [hive-addon.mount :as mount]
            [hive-addon.protocol :as addon]
            [hive-olympus-emacs.manifest-test :as mt]
            [hive-vessel.dialect.elisp :as elisp]
            [hive-vessel.executor.emacsclient :as ec])
  (:import (java.util.concurrent TimeUnit)))

(def socket
  "Socket name of the private probe daemon the eval fn targets."
  (atom "olympus-probe"))

(defn- unwrap [printed]
  (if (str/starts-with? printed "\"") (edn/read-string printed) printed))

(defn probe-eval!
  "hive-emacs.client/eval-elisp!'s contract over emacsclient -s @socket:
   the printed result (string quotes unwrapped), {:error :timeout :msg ..}
   on timeout, a thrown ex-info otherwise."
  [code]
  (try
    (unwrap (ec/eval-elisp {:socket-name @socket :timeout-ms 10000} code))
    (catch clojure.lang.ExceptionInfo e
      (if (:timeout-ms (ex-data e))
        {:error :timeout :msg (ex-message e)}
        (throw (ex-info "Elisp evaluation failed" {:error (ex-data e) :code code}))))))

(defn- answers? [socket-name]
  (try (= "t" (ec/eval-elisp {:socket-name socket-name :timeout-ms 2000} "t"))
       (catch Exception _ false)))

(defn start-daemon!
  "Start `emacs -Q --fg-daemon=SOCKET-NAME` and wait until it answers.
   Refuses when a server already answers on that socket."
  [socket-name]
  (when (answers? socket-name)
    (throw (ex-info "a server already answers on the probe socket; refusing" {:socket socket-name})))
  (reset! socket socket-name)
  (let [p (.start (doto (ProcessBuilder. ["emacs" "-Q" (str "--fg-daemon=" socket-name)])
                    (.redirectErrorStream true)
                    (.redirectOutput (java.io.File/createTempFile "olympus-probe" ".log"))))]
    (loop [tries 75]
      (cond
        (answers? socket-name) {:process p :socket socket-name}
        (zero? tries) (do (.destroyForcibly p)
                          (throw (ex-info "probe daemon never answered" {:socket socket-name})))
        :else (do (Thread/sleep 200) (recur (dec tries)))))))

(defn stop-daemon!
  "kill-emacs the probe daemon, then make sure the process is gone."
  [{:keys [^Process process socket]}]
  (try (ec/eval-elisp {:socket-name socket :timeout-ms 3000} "(kill-emacs)") (catch Exception _ nil))
  (when-not (.waitFor process 10 TimeUnit/SECONDS)
    (.destroyForcibly process))
  {:alive? (.isAlive process) :exit (when-not (.isAlive process) (.exitValue process))})

(defn- buffer-text [buffer-name]
  (probe-eval! (str "(let ((b (get-buffer " (elisp/string-literal buffer-name) ")))"
                    " (and b (with-current-buffer b (buffer-substring-no-properties (point-min) (point-max)))))")))

(defn- olympus-buffers []
  (->> (probe-eval! (str "(mapconcat #'identity (seq-filter (lambda (n) (string-prefix-p \"*hive:olympus/\" n))"
                         " (mapcar #'buffer-name (buffer-list))) \"\\n\")"))
       str/split-lines
       (remove str/blank?)
       sort
       vec))

(defn run-probe!
  "Mount core (N stub agents) + the shipped brick against the probe daemon,
   read back the panel buffers, tear down. Returns the evidence."
  [n]
  (reset! mt/roster-size n)
  (let [spec (mt/brick-spec 'hive-olympus-emacs.probe/probe-eval!)
        [host report] (mt/mount-all [spec (mt/core-spec) mt/emacs-stub-spec])
        core (hive-addon.mount.port/registered host "hive.olympus")
        brick (hive-addon.mount.port/registered host "hive.olympus.emacs")]
    (try
      (let [buffers (olympus-buffers)]
        {:mount-ok? (:ok? report)
         :route (get-in (addon/health brick) [:details :route])
         :presenter (get-in (addon/health core) [:details :presenters "hive.emacs"])
         :buffers buffers
         :text (into {} (map (juxt identity buffer-text)) buffers)})
      (finally
        (mount/teardown! host (:order report))
        (mt/shutdown-all! host ["hive.olympus.emacs" "hive.olympus"])))))

(defn -main [& _]
  (let [daemon (start-daemon! "olympus-probe")
        evidence (try (run-probe! 6) (finally (println :daemon (stop-daemon! daemon))))
        texts (:text evidence)
        ok? (and (:mount-ok? evidence)
                 (= :configured-resolver (:route evidence))
                 (= :live (get-in evidence [:presenter :status]))
                 (= #{"*hive:olympus/tab-1*" "*hive:olympus/tab-2*"} (set (:buffers evidence)))
                 (str/includes? (str (get texts "*hive:olympus/tab-1*")) "Olympus  tab 1/2")
                 (str/includes? (str (get texts "*hive:olympus/tab-2*")) "Olympus  tab 2/2"))]
    (prn evidence)
    (println (if ok? "PROBE OK" "PROBE FAILED"))
    (shutdown-agents)
    (System/exit (if ok? 0 1))))
