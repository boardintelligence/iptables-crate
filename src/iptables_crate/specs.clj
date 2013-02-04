(ns iptables-crate.specs
  "Server and group specs for working with iptables (and sysctl)"
  (:require [iptables-crate.iptables :as ipt]
            [pallet.api :as api]))

(def
  ^{:doc "Server spec for a server using iptables rules (and sysctl)."}
  iptables-server
  (api/server-spec
   :phases
   {:configure (api/plan-fn
                (ipt/install-iptables-rules)
                (ipt/install-sysctl-config))
    ;; good to have a way to only apply new iptables rules without any other configure stuff
    :install-iptables-rules (api/plan-fn
                             (ipt/install-iptables-rules)
                             (ipt/install-sysctl-config))
    :flush-iptables-rules (api/plan-fn (ipt/flush-iptables-rules))}))
