(ns pallet-hadoop.node
  (:use [pallet.crate.automated-admin-user :only (automated-admin-user)]
        [pallet.extensions :only (phase def-phase-fn)]
        [pallet.crate.java :only (java)]
        [pallet.core :only (make-node lift converge)]
        [clojure.set :only (union)])
  (:require [pallet.crate.hadoop :as h]))

;; ## Hadoop Cluster Configuration

;; ### Utilities
;;
;; Hadoop doesn't really have too many requirements for its nodes --
;; we do have to layer a few properties onto the base nodespec,
;; however. The ports are the most important element. If the user
;; supplies some default set of ports, we'd like to some way to take
;; the union of the required hadoop ports and the supplied ports from
;; the base node. The following function converts all supplied
;; sequences to sets, takes the union, and converts the results back
;; into a vector. Any type of collection is fine, for `xs`.

(defn merge-to-vec
  "Returns a vector containing the union of all supplied collections."
  [& xs]
  (println xs)
  (apply (comp vec union) (map set xs)))

;; TODO -- discuss aliasing.
;; ### Defaults
;;
;; We've aliased `:slavenode` to `:datanode` and `:tasktracker`, as
;; these usually come together.

(def hadoop-aliases
  {:slavenode [:datanode :tasktracker]})

(defn expand-aliases
  "Returns a sequence of hadoop roles, with any aliases replaced by
  the corresponding roles. `:slavenode` is the only alias, currently,
  and expands out to `:datanode` and `:tasktracker`."
  [roles]
  (flatten (replace hadoop-aliases (conj roles :default))))

;; Hadoop requires certain ports to be accessible, as discussed
;; [here](http://goo.gl/nKk3B) by the folks at Cloudera. We provide
;; sets of ports that can be merged based on the hadoop roles that
;; some node-spec wants to use.

(def hadoop-ports
  {:default #{22 80}
   :namenode #{50070 8020}
   :datanode #{50075 50010 50020}
   :jobtracker #{50030 8021}
   :tasktracker #{50060}
   :secondarynamenode #{50090 50105}})

(def ^{:doc "Set of all hadoop `master` level tags. Used to assign
  default counts to master nodes, and to make sure that no more than
  one of each exists."}
  hadoop-masters
  #{:namenode :jobtracker})

(defn master?
  "Predicate that determines whether or not the given sequence of
  roles contains a master node tag."
  [roleseq]
  (boolean (some hadoop-masters roleseq)))

;; TODO -- version should be a property, with a default! something
;; like {:keys [version] :or [version :cloudera]}.
;;

(defn hadoop-phases
  "Returns a map of all possible hadoop phases. IP-type specifies "
  [ip-type jt-tag nn-tag properties]
  (let [configure (phase
                   (h/configure ip-type
                                nn-tag
                                jt-tag
                                properties))]
    {:bootstrap automated-admin-user
     :configure (phase (java :jdk)
                       (h/install :cloudera)
                       configure)
     :reinstall (phase (h/install :cloudera)
                       configure)
     :reconfigure configure
     :publish-ssh-key h/publish-ssh-key
     :authorize-jobtracker (phase (h/authorize-tag jt-tag))
     :start-mapred h/task-tracker
     :start-hdfs h/data-node
     :start-jobtracker h/job-tracker
     :start-namenode (phase (h/name-node "/tmp/node-name/data"))}))

(def ^{:doc "Map of all hadoop roles to sets of required phases."}
  role->phase-map
  {:default #{:bootstrap
              :reinstall
              :configure
              :reconfigure
              :authorize-jobtracker}
   :namenode #{:start-namenode}
   :datanode #{:start-hdfs}
   :jobtracker #{:publish-ssh-key :start-jobtracker}
   :tasktracker #{:start-mapred}})

;; Finally, the big method! By providing a base node and a vector of
;; hadoop "roles", the user gets back a new node-spec with all
;; required hadoop modifications.

;; TODO -- Is this going to get confusing? CAN WE ASSUME that all
;; aliases have been expanded, or will it clearer if we do otherwise?

(defn hadoop-machine-spec
  "Generates a node spec for a hadoop node, merging together the given
  basenode with the required properties for the defined hadoop
  roles. (We assume at this point that all aliases have been expanded.)"
  [base-spec roles]
  (let [ports ((comp vec distinct) (mapcat hadoop-ports roles))]
    (merge-with merge-to-vec
                base-spec
                {:inbound-ports ports})))

(defn roles->phases
  "Converts a sequence of hadoop roles into a sequence of the unique
  phases required by a node trying to take on each of these roles."
  [roles]
  ((comp vec distinct) (mapcat role->phase-map roles)))

(defn hadoop-server-spec
  "Returns a map of all all hadoop phases -- we'll need to modify the
  name, here, and change this to compose with over server-specs."
  [ip-type jt-tag nn-tag properties roles]
  (let [all-phases (hadoop-phases ip-type jt-tag nn-tag properties)]
    (select-keys all-phases
                 (roles->phases roles))))

;; We have a precondition here that makes sure at least one of the
;;defined roles exists as a hadoop roles.
;;
                                        ;
;; We need to provide a way for the user to send in a base server-spec
;;and a base machine-spec, so we can layer on top of those.
;;
;; TODO -- take tag, ip-type, jt-tag, spec, etc... don't merge within
;;here. Do that in describe, or something.

(defn hadoop-spec
  "Equivalent to `server-spec` in the new pallet."
  [tag ip-type jt-tag nn-tag base-spec base-props {:keys [spec roles props]
                                                   :or {spec {}
                                                        props {}}}]
  {:pre [(some (set (keys role->phase-map)) (expand-aliases roles))]}
  (let [roles (expand-aliases roles)
        machine-spec (hadoop-machine-spec (merge base-spec spec) roles)
        props (h/merge-config base-props props)
        phase-map (hadoop-server-spec ip-type jt-tag nn-tag props roles)
        phase-seq (apply concat phase-map)]
    (apply make-node tag machine-spec phase-seq)))

(defn hadoop-node
  "Generates a map representation of a Hadoop node, employing sane defaults."
  [roles & [count & {:keys [base-spec props]
                     :or {base-spec {} props {}}
                     :as options}]]
  {:pre [(or count (master? roles))]}
  {:node (merge {:roles roles} options)
   :count (or count (when (master? roles) 1))})

(def slave-node (partial hadoop-node [:slavenode]))

;; TODO -- We'll want to think about a way to check that only one role
;; for each master node exists.
(defn roles->tags
  "Accepts a map of `tag, hadoop-node` pairs and a sequence of roles,
  and returns a sequence of the corresponding node tags. A
  postcondition is implemented to make sure that every role exists in
  the given node-def map."
  [role-seq node-defs]
  {:post [(= (count %) (count role-seq))]}
  (let [find-tag (fn [k]
                   (some (fn [[tag def]]
                           (when (some #{k} (get-in def [:node :roles]))
                             tag))
                         node-defs))]
    (remove nil? (map find-tag role-seq))))

(defn cluster->node-map
  "Converts a cluster to `node-map` represention, for use in a call to
  `pallet.core/converge`. Supported tasks at this time are `:boot` and `:kill`.

    :boot => uses the counts defined in the cluster
    :kill => sets map values to zero, effectively killing the cluster on converge."
  [cluster task]
  (let [[node-defs base-spec base-props ip-type] (map cluster [:nodedefs :base-machine-spec :base-props :ip-type])
        [jt-tag nn-tag] (roles->tags [:jobtracker :namenode] node-defs)]
    (into {}
          (for [[tag config] node-defs
                :let [[count node] (map config [:count :node])
                      node-def (hadoop-spec tag ip-type jt-tag nn-tag base-spec base-props node)]]
            (case task
                  :boot [node-def count]
                  :kill [node-def 0])))))

;; TODO -- better name! Also, maybe the task input could be :lift, for
;; describe.
(defn cluster->node-set
  "Converts a cluster to `node-set` represention, for use in a call to
  `pallet.core/lift`."
  [cluster]
  (keys (cluster->node-map cluster :kill)))

;; ### High Level Converge and Lift

;; TODO -- more description
(defn converge-cluster
  [cluster action & options]
  (apply (partial converge (cluster->node-map cluster action)) options))

(defn boot-cluster [cluster & options]
  (apply (partial converge-cluster cluster :boot
                  :phase [:configure
                          :publish-ssh-key
                          :authorize-jobtracker])
         options))

(defn kill-cluster [cluster & options]
  (apply (partial converge-cluster cluster :kill) options))

(defn lift-cluster
  [cluster phaseseq & options]
  (apply (partial lift (cluster->node-set cluster)
                  :phase phaseseq)
         options))

(defn start-cluster [cluster & options]
  (apply (partial lift-cluster cluster [:start-namenode
                                        :start-hdfs
                                        :start-jobtracker
                                        :start-mapred])
         options))

;; EXPLAIN
;; TODO -- add overall cluster default hadoop properties.

(defn cluster-spec [ip-type nodedefs & {:keys [base-machine-spec base-props]
                                        :or [base-machine-spec {}
                                             base-props {}]
                                        :as options}]
  (merge options {:ip-type ip-type
                  :nodedefs nodedefs}))


(defn test-cluster
  "This generates an example cluster map."
  [ip-type nodecount]
  (cluster-spec ip-type
                {:master (hadoop-node [:namenode :jobtracker])
                 :slaves (slave-node nodecount)}
                :base-machine-spec {:os-family :ubuntu
                                    :os-version-matches "10.10"
                                    :os-64-bit true}
                :base-props {:mapred-site {:mapred.tasks.timeout 300000
                                           :mapred.tasktracker.map.tasks.maximum 20
                                           :mapred.tasktracker.reduce.tasks.maximum 20}}))