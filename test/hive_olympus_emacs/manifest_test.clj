(ns hive-olympus-emacs.manifest-test
  "The shipped manifest through the real mounter against a stub hive.olympus
   (a presenter seat) and a stub hive.emacs (no vessel hooks, so delivery must
   take the :configured-resolver route), then against the real hive.olympus
   core manifest. The eval fn is swapped for one honouring the measured
   contract of hive-emacs.client/eval-elisp!: a String on success, a
   {:error :timeout|:circuit-open :msg ..} map, or a thrown ex-info."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hive-addon.mount :as mount]
            [hive-addon.mount.port :as mount-port]
            [hive-addon.protocol :as addon]))

(defrecord StubAddon [id hook-map]
  addon/IAddon
  (addon-id [_] id)
  (addon-type [_] :native)
  (capabilities [_] #{})
  (initialize! [_ _] {:success? true})
  (shutdown! [_] nil)
  (tools [_] [])
  (schema-extensions [_] [])
  (health [_] {:status :ok})
  (excluded-tools [_] #{})
  (hooks [_] hook-map))

(def seat (atom {}))
(def payloads (atom []))
(def eval-answer
  "(fn [payload]) standing in for the Emacs side of eval-elisp!."
  (atom nil))

(defn buffer-created
  "The panel buffer name an elisp show-panel payload creates, or nil."
  [payload]
  (second (re-find #"get-buffer-create \"([^\"]+)\"" payload)))

(defn fake-eval-elisp!
  "eval-elisp! success shape: the printed result, string quotes unwrapped."
  [payload]
  (swap! payloads conj payload)
  ((or @eval-answer (fn [p] (or (buffer-created p) "t"))) payload))

(def panel
  {:op :ui/show-panel :panel/id "olympus/tab-1"
   :doc {:doc/title "Olympus  tab 1/1  (0 agents: 0 working, 0 blocked, 0 error, 0 idle)"
         :doc/blocks [{:block/type :para :text "No active agents" :tone :muted}]}})

(defn olympus-stub-ctor [_]
  (->StubAddon "hive.olympus"
               {:olympus/register-presenter! (fn [id target] (swap! seat assoc id target) (target [panel]) id)
                :olympus/unregister-presenter! (fn [id] (swap! seat dissoc id) id)}))

(defn emacs-stub-ctor [_] (->StubAddon "hive.emacs" {}))

(def roster-size (atom 0))

(defn stub-roster
  "N agents cycling through every status, N read from roster-size."
  []
  (mapv (fn [i]
          {:agent/id (str "demo-" i)
           :agent/name (str "demo-" i)
           :agent/status (nth [:working :blocked :error :idle] (mod i 4))})
        (range 1 (inc @roster-size))))

(defn manifest [file]
  (some-> (io/resource (str "META-INF/hive-addons/" file)) slurp edn/read-string))

(defn brick-spec
  "The shipped brick manifest, its eval fn swapped for EVAL-SYM."
  [eval-sym]
  (update (manifest "hive-olympus-emacs.edn") :addon/config assoc :olympus/eval-fn eval-sym))

(def emacs-stub-spec
  {:addon/id "hive.emacs" :addon/type :native
   :addon/init-ns "hive-olympus-emacs.manifest-test" :addon/init-fn "emacs-stub-ctor"
   :addon/capabilities #{:editor}})

(def olympus-stub-spec
  {:addon/id "hive.olympus" :addon/type :native
   :addon/init-ns "hive-olympus-emacs.manifest-test" :addon/init-fn "olympus-stub-ctor"
   :addon/capabilities #{}})

(defn core-spec
  "The real hive.olympus manifest, ticker off, roster from stub-roster."
  []
  (update (manifest "hive-olympus.edn") :addon/config assoc
          :olympus/refresh-ms 0
          :olympus/roster-fn 'hive-olympus-emacs.manifest-test/stub-roster))

(defn mount-all [specs]
  (let [host (mount/atom-mount-host)
        report (mount/mount! (mount/solve specs) host)]
    [host report]))

(defn shutdown-all! [host ids]
  (doseq [id ids]
    (when-let [a (mount-port/registered host id)]
      (try (addon/shutdown! a) (catch Throwable _ nil)))))

(defn- reset-all! []
  (reset! seat {})
  (reset! payloads [])
  (reset! eval-answer nil)
  (reset! roster-size 0))

(deftest the-manifest-is-data-only
  (let [spec (manifest "hive-olympus-emacs.edn")]
    (is (= "hive.olympus.emacs" (:addon/id spec)))
    (is (= "hive-olympus.harness" (:addon/init-ns spec)))
    (is (= "addon-ctor" (:addon/init-fn spec)))
    (is (= {:olympus/host "hive.emacs"
            :olympus/target-resolver 'hive-olympus.harness/eval-port-target
            :olympus/eval-fn 'hive-emacs.client/eval-elisp!
            :olympus/dialect :elisp}
           (:addon/config spec)))
    (is (= #{"hive.olympus" "hive.emacs"} (:addon/dependencies spec)))
    (is (= :foss (:addon/trust-class spec)))
    (is (some? (requiring-resolve 'hive-olympus.harness/eval-port-target))
        "the named resolver exists in the core")
    (is (some #(= "hive.olympus.emacs" (:addon/id %)) (:specs (mount/discover-specs))))))

(deftest mounts-against-stubs-and-delivers-elisp
  (reset-all!)
  (let [[host report] (mount-all [(brick-spec 'hive-olympus-emacs.manifest-test/fake-eval-elisp!)
                                  olympus-stub-spec
                                  emacs-stub-spec])
        brick (mount-port/registered host "hive.olympus.emacs")]
    (try
      (is (:ok? report) (pr-str (:mounted report)))
      (is (= "hive.olympus.emacs" (last (:order report))))
      (is (contains? @seat "hive.emacs") "registered under the host id")
      (testing "the :ui/show-panel reaches the eval port as one elisp program"
        (is (= 1 (count @payloads)))
        (let [payload (first @payloads)]
          (is (string? payload))
          (is (= "*hive:olympus/tab-1*" (buffer-created payload)))
          (is (str/includes? payload "Olympus  tab 1/1"))
          (is (str/starts-with? payload "(let ") "a single form: emacsclient --eval reads one")))
      (is (= :configured-resolver (get-in (addon/health brick) [:details :route])))
      (is (empty? (:errors (mount/teardown! host (:order report)))))
      (is (empty? @seat) "teardown unregisters the presenter")
      (finally (when brick (addon/shutdown! brick))))))

(deftest eval-elisp-failure-shapes-surface-as-a-failed-delivery
  (reset-all!)
  (let [[host report] (mount-all [(brick-spec 'hive-olympus-emacs.manifest-test/fake-eval-elisp!)
                                  olympus-stub-spec
                                  emacs-stub-spec])
        target (get @seat "hive.emacs")
        failure (fn [answer]
                  (reset! eval-answer answer)
                  (try (target [panel]) nil
                       (catch clojure.lang.ExceptionInfo e (ex-data e))))]
    (try
      (is (:ok? report))
      (doseq [[shape answer] {:timeout (fn [_] {:error :timeout :msg "Emacsclient call timed out after 5000ms"})
                              :circuit-open (fn [_] {:error :circuit-open :msg "Circuit breaker open"})
                              :thrown (fn [p] (throw (ex-info "Elisp evaluation failed" {:error "boom" :code p})))}]
        (testing (str shape)
          (let [data (failure answer)]
            (is (= :vessel-dispatch-failed (:reason data)))
            (is (= "hive.emacs" (:olympus/host data)))
            (is (= :configured-resolver (:route data)))
            (is (= :execute-threw (get-in data [:failure :failure/reason]))))))
      (finally
        (mount/teardown! host (:order report))
        (shutdown-all! host ["hive.olympus.emacs"])))))

(deftest mounts-against-the-real-core
  (reset-all!)
  (reset! roster-size 6)
  (let [[host report] (mount-all [(brick-spec 'hive-olympus-emacs.manifest-test/fake-eval-elisp!)
                                  (core-spec)
                                  emacs-stub-spec])
        core (mount-port/registered host "hive.olympus")]
    (try
      (is (:ok? report) (pr-str (:mounted report)))
      (testing "six agents are two tabs, each one buffer"
        (is (= ["*hive:olympus/tab-1*" "*hive:olympus/tab-2*"] (mapv buffer-created @payloads)))
        (is (str/includes? (first @payloads) "Olympus  tab 1/2"))
        (is (str/includes? (second @payloads) "Olympus  tab 2/2")))
      (is (= {:status :live :deliveries 1}
             (get-in (addon/health core) [:details :presenters "hive.emacs"])))
      (mount/teardown! host (:order report))
      (finally (shutdown-all! host ["hive.olympus.emacs" "hive.olympus"])))))

(deftest the-verbatim-manifest-without-hive-emacs-has-no-route
  (reset-all!)
  (let [[host report] (mount-all [(manifest "hive-olympus-emacs.edn") (core-spec) emacs-stub-spec])
        core (mount-port/registered host "hive.olympus")]
    (try
      (is (nil? (try (requiring-resolve 'hive-emacs.client/eval-elisp!) (catch Throwable _ nil)))
          "hive-emacs is not on this classpath")
      (let [presenter (get-in (addon/health core) [:details :presenters "hive.emacs"])]
        (is (= :degraded (:status presenter)) (pr-str presenter))
        (is (str/includes? (str (:error presenter)) "no delivery route to hive.emacs") (pr-str presenter)))
      (mount/teardown! host (:order report))
      (finally (shutdown-all! host ["hive.olympus.emacs" "hive.olympus"])))))
