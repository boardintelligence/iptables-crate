(ns iptables-crate.iptables
  "Crate with functions for setting up and configuring iptables firewalls")

(def empty-ruleset
  {:mangle {:prerouting []
            :postrouting []
            :output []
            :input []
            :forward []}
   :nat {:prerouting []
         :postrouting []
         :output []}
   :filter {:forward []
            :input []
            :output []}})

(defn- state-arg-handler
  [key value]
  (let [values (if (vector? value) value (vector value))]
    (str "-m state --state "
         (clojure.string/join "," (map #(.toUpperCase (name %1)) values)))))

(defn- src-or-dst-arg-handler
  [key value]
  (cond
   (= :anywhere value) (str "--" (name key) " 0.0.0.0/0")
   (string? value) (str "--" (name key) " " value)
   :else (throw (IllegalArgumentException.
                 (format "Unknown type of arg for source or destination: %s"
                         (.toString value))))))

(defn- limit-arg-handler
  [key value]
  (str "-m limit --limit " value))

(defn- default-convert-value
  [value]
  (cond
   (keyword? value)
   (name value)

   (and (string? value)
        (not (re-find #" " value)))
   value

   (and (string? value)
        (re-find #" " value))
   (str "\"" value "\"")

   (number? value)
   (str value)

   :else (throw (IllegalArgumentException. (format "Unknown type of parameter value: %s" (.toString value))))))

(defn- default-arg-handler
  [key value]
  (str "--" (name key) " " (default-convert-value value)))

;; TODO: special cases to add in
;;   :public-ports --> 1024:65535, both sport and dport
;;   :private-ports --> 0:1023, both sport and dport

(defn- process-rule-arg
  "Convert 1 keyword/value pair to string representation."
  [[key value]]
  (cond
   (= key :state) (state-arg-handler key value)
   (= key :source) (src-or-dst-arg-handler key value)
   (= key :destination) (src-or-dst-arg-handler key value)
   (= key :log-prefix) (format "--log-prefix \"IPTC: %s\"" value)
   (= key :limit) (limit-arg-handler key value)
   :else (default-arg-handler key value)))

(defn- process-rule-args
  "Convert our args into iptables format."
  [args]
  (clojure.string/join " " (map process-rule-arg args)))

(defn accept
  "Produce a -j ACCEPT line"
  [ & {:as args}]
  (str "-j ACCEPT " (process-rule-args args)))

(defn drop
  "Produce a -j DROP line"
  [ & {:as args}]
  (str "-j DROP " (process-rule-args args)))

(defn log
  "Produce a -j LOG line"
  [ & {:as args}]
  (str "-j LOG " (process-rule-args args)))

(defn masquerade
  "Produce a -j MASQUERADE line"
  [ & {:as args}]
  (str "-j MASQUERADE " (process-rule-args args)))

(defn- produce-one-line
  [table q-name rule]
  (str "iptables -t " (name table) " -A " (.toUpperCase (name q-name)) " " rule))

(defn- produce-one-table-queue
  "Produce output for one combination of TABLE and QUEUE"
  [rules [table q-name]]
  (let [current-rules (get-in rules [table q-name])]
    (map (partial produce-one-line table q-name) (filter #(not (nil? %1)) current-rules))))

(defn- table-queue-pairs
  "Produce a sequence of table/queue pairs for all combinations we know of."
  []
  (reduce concat (map (fn [[table queues]]
                        (map (fn [q-name]
                               [table q-name]) (keys queues)))
                      empty-ruleset)))

(defn produce-iptables-output
  "Convert a rule set to iptables invoctions."
  [rules]
  (let [full-rules (merge empty-ruleset rules)
        lines (reduce concat (map (partial produce-one-table-queue full-rules) (table-queue-pairs)))
        content (clojure.string/join "\n" lines)]
    (str "#!/bin/bash\n# iptable rules generated by iptables-crate for pallet\n" content)))

(defplan install-iptables-rules
  "Install iptables rules on a given host."
  []
  (let [hostname (crate/target-name)
        rules (env/get-environment [:host-config hostname :firewall-rules])
        rules-output (produce-iptables-output rules)]
    (actions/remote-file "/etc/init.d/iptables-crate-firewall-rules"
                         :content rules-output
                         :literal true
                         :mode "0755")
    (actions/remote-file "/etc/init/iptables-crate.conf"
                         :local-file (utils/resource-path "iptables/iptables-crate.conf")
                         :literal true)
    (actions/remote-file "/etc/rsyslog.d/01-iptables-crate.conf"
                         :local-file (utils/resource-path "iptables/iptables-crate.rsyslog")
                         :literal true
                         :no-versioning true
                         :mode "0644")
    (actions/remote-file "/etc/logrotate.d/iptables-crate"
                         :local-file (utils/resource-path "iptables/iptables-crate.logrotate")
                         :literal true
                         :no-versioning true
                         :mode "0644")
    (actions/exec-checked-script
     "Update symlink to rules and restart firewall"
     (rm -f "/etc/init.d/iptables-crate")
     (ln -s "/etc/init.d/iptables-crate-firewall-rules" "/etc/init.d/iptables-crate")
     (if-not (= @(pipe (status iptables-crate)
                   (grep running)) "")
       (stop iptables-crate))
     (start iptables-crate)
     (service rsyslog restart))))

(defplan flush-iptables-rules
  "Turn off all iptables rules (temporarily or persistently)."
  []
  (let [hostname (crate/target-name)
        persist (env/get-environment [:persist-rules-flush] false)]
    (actions/remote-file "/usr/bin/iptables-crate-flush-rules"
                         :local-file (utils/resource-path "iptables/iptables-crate-flush-rules")
                         :literal true
                         :mode "0755")
    (actions/exec-checked-script
     "Flush iptables rules"
     ("/usr/bin/iptables-crate-flush-rules"))
    (if persist
      (actions/exec-checked-script
       "Remove symlink to rules to disable them"
       (rm -f "/etc/init.d/iptables-crate")))))

(defn- set-sysctl-values
  [variables value]
  (doseq [variable variables]
    (let [start-of-line (format "^%s" variable)
          set-expression (format "%s=%d" variable value)]
      (actions/sed "/etc/sysctl.conf" {(format "%s\\s*=.*" variable) set-expression})
      (actions/exec-checked-script
       "Add to sysctl if setting not present, enact in current session"
       (if (= @(grep ~start-of-line "/etc/sysctl.conf") "")
         (echo ~set-expression " >> /etc/sysctl.conf"))
       (sysctl -w ~set-expression)))))

(defplan install-sysctl-config
  "Make sure sysctl knobs are tuned as we wish."
  []
  (let [hostname (crate/target-name)
        rules (env/get-environment [:host-config hostname :firewall-rules :sysctl-conf])
        turn-ons (:turn-on rules)
        turn-offs (:turn-off rules)]
    (set-sysctl-values turn-ons 1)
    (set-sysctl-values turn-offs 0)))
