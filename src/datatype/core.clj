(ns datatype.core
    (:use [clojure.core.match :only [match]]
          [clojure.string :only [join lower-case split]]))

(defn- constant-value
    [s]
    (keyword (str *ns*) (str s)))

(defn- factory-symbol
    [s]
    (let [parts (split (str s) #"[.]")
          ns (join "." (butlast parts))
          factory (str "->" (last parts))]
        (symbol (if (empty? ns)
                    factory
                    (str ns "/" factory)))))

(defn- mangle-record-fields
    [name args]
    (map #(symbol (lower-case (str name "-" %))) args))

(defn- make-constructor-strict
    [type constructor]
    (if (symbol? constructor)
        ;; constant constructor
        `(def ~(vary-meta  constructor assoc ::datatype type)
             ~(constant-value constructor))
        ;; factory constructor
        (let [[name & args] constructor
              margs (mangle-record-fields name args)]
            `(do
                 (defrecord ~name [~@margs])
                 (alter-meta! (var ~(factory-symbol name)) assoc ::datatype ~type)))))

(defn- make-constructor-lazy
    [type constructor]
    (if (symbol? constructor)
        ;; constant constructor
        `(def ~(vary-meta  constructor assoc ::datatype type ::lazy true)
             (delay ~(constant-value constructor)))
        ;; factory constructor
        (let [[name & args] constructor
              margs (mangle-record-fields name args)]
            `(do
                 (defrecord ~name [~@margs])
                 (defmacro ~(vary-meta (factory-symbol name) assoc ::datatype type ::lazy true) [~@margs]
                     (list 'delay (list 'new ~name ~@margs)))))))

(defn- has-lazy-meta?
    [constructor]
    (cond (symbol? constructor) (::lazy (meta constructor))
          (list? constructor) (recur (first constructor))))
    
(defn- make-constructor
    [type mode constructor]
    (if (or (= mode :lazy) (has-lazy-meta? constructor))
        (make-constructor-lazy type constructor)
        (make-constructor-strict type constructor)))

(defmacro defdatatype
    [type & constructors]
    `(do
         ~@(map (partial make-constructor type :strict) constructors)))

(defmacro deflazy
    [type & constructors]
    `(do
         ~@(map (partial make-constructor type :lazy) constructors)))

(defn- constructor?
    [symbol]
    (contains? (meta (resolve symbol)) ::datatype))

(defn- lazy?
    [symbol]
    (contains? (meta (resolve symbol)) ::lazy))

(defn- constant?
    [condition]
    (and (symbol? condition) (constructor? condition)))

(defn- lazy-constant?
    [condition]
    (and (constant? condition) (lazy? condition)))

(defn- factory?
    [condition]
    (and (vector? condition)
         (let [[constructor & _] condition
               factory (factory-symbol constructor)]
             (constructor? factory))))

(defn- lazy-factory?
    [condition]
    (and (factory? condition)
         (let [[constructor & _] condition
               factory (factory-symbol constructor)]
             (lazy? factory))))

(defn- dollar-expr?
    [condition]
    (and (list? condition) (= '$ (first condition))))

(defn- as-expr?
    [condition]
    (and (list? condition) (= :as (second condition))))

(defn- lazy-condition?
    [condition]
    (or (lazy-constant? condition)
        (lazy-factory? condition)
        (dollar-expr? condition)
        (and (as-expr? condition) (lazy-condition? (first condition)))))

(defn- factory-args
    [factory]
    (first (:arglists (meta (resolve factory)))))

(defn- transform-constant
    [constant]
    (keyword (subs (str (resolve constant)) 2)))

(declare transform-condition)

(defn- transform-factory
    [[constructor & params]]
    (let [args  (->> constructor
                     factory-symbol
                     factory-args
                     (map keyword))
          pairs (->> params
                     (map transform-condition)
                     (map vector args)
                     (filter (fn [[_ param]] (not= param '_))))]
        (into {} pairs)))

(defn- transform-dollar
    [[dollar expr]]
    (transform-condition expr))

(defn- transform-as
    [[expr as symbol]]
    (list (transform-condition expr) :as symbol))

(defn- transform-condition
    [condition]
    (cond (constant? condition)    (transform-constant condition)
          (factory? condition)     (transform-factory condition)
          (dollar-expr? condition) (transform-dollar condition)
          (as-expr? condition)     (transform-as condition)
          :else                    condition))

(defn- row-type
    [row]
    (cond (= :else row)                         ::else-row
          (and (list? row) (= :or (first row))) ::or-row
          :else                                 ::normal-row))

(defmulti ^:private transform row-type)
(defmulti ^:private accumulate row-type)
(defmulti ^:private normal-row row-type)

(defmethod transform ::normal-row
    [row]
    [(vec (map transform-condition row))])

(defmethod accumulate ::normal-row
    [row]
    [row])

(defmethod normal-row ::normal-row
    [row]
    row)

(defmethod transform ::or-row
    [[ _ & rows]]
    [`(:or ~@(mapcat transform rows))])

(defmethod accumulate ::or-row
    [[ _ & rows]]
    rows)

(defmethod normal-row ::or-row
    [[_ row]]
    row)

(defmethod transform ::else-row
    [row]
    row)

(defmethod accumulate ::else-row
    [_]
    [])

(defn- create-local-bindings
    [args new-args]
    (->> (interleave args new-args)
         (partition 2)
         (filter (partial apply not=))
         (mapcat (fn [[arg new]] `(~new (force ~arg))))))

(defn- make-args-from-rules
    [rules]
    (-> rules first normal-row count (take (repeatedly #(gensym "defun-"))) vec))

(defn- force-or-remove-delay
    [action]
    (if (and (list? action) (= (first action) '$))
        (second action)
        `(force ~action)))

(defmacro $
    [expr]
    `(delay ~expr))

(defmacro caseof
    [args & rules]
    (let [row-action-pairs  (partition 2 rules)
          rows              (map first row-action-pairs)
          actions           (map second row-action-pairs)
          accumulated-rows  (mapcat accumulate rows)
          transposed-rows   (apply map vector accumulated-rows)
          need-force        (map #(some lazy-condition? %) transposed-rows)
          new-args          (map #(if %1 (gensym "forced-") %2) need-force args)
          local-bindings    (create-local-bindings args new-args)
          transformed-rows  (map transform rows)
          transformed-rules (interleave transformed-rows actions)]
        `(let [~@(create-local-bindings args new-args)]
             (match [~(vec new-args)]
                    ~@transformed-rules))))

(defmacro defun
    [name & rules]
    (let [args (make-args-from-rules rules)]
        `(defn ~name ~args
             (caseof ~args
                     ~@rules))))

(defmacro defunlazy
    [name & rules]
    (let [args                (make-args-from-rules rules)
          row-action-pairs    (partition 2 rules)
          rows                (map first row-action-pairs)
          actions             (map second row-action-pairs)
          transformed-actions (map force-or-remove-delay actions)
          transformed-rules   (interleave rows transformed-actions)]
        `(defn ~name ~args
             ($ (caseof ~args
                        ~@transformed-rules)))))

