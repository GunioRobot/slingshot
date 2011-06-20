(ns slingshot.core
  (import (slingshot Stone)))

(defn- clause-type [x]
  (when (seq? x) (#{'catch 'finally} (first x))))

(defn- partition-body [body]
  (let [[e c f s] (partition-by clause-type body)
        [e c f s] (if (-> (first e) clause-type nil?) [e c f s] [nil e c f])
        [c f s] (if (-> (first c) clause-type (= 'catch)) [c f s] [nil c f])
        [f s] (if (-> (first f) clause-type (= 'finally)) [f s] [nil f])]
    (when (or s (> (count f) 1))
      (throw (Exception. (str "try+ form must match: "
                              "(try+ expr* catch-clause* finally-clause?)"))))
    [e c f]))

(defn- class-name? [x]
  (and (symbol? x) (class? (resolve x))))

(defn- type-spec? [x]
  (and (map? x) (= 1 (count x))))

(defn- cond-clause [[_ selector binding-form & catch-body]]
  [(cond (class-name? selector)
         `(instance? ~selector (:obj ~'&throw-context))
         (type-spec? selector)
         (let [[hierarchy parent] (first selector)]
           (if (nil? hierarchy)
             `(isa? (type (:obj ~'&throw-context)) ~parent)
             `(isa? ~hierarchy (type (:obj ~'&throw-context)) ~parent)))
         :else
         `(~selector (:obj ~'&throw-context)))
   `(let [~binding-form (:obj ~'&throw-context)]
      ~@catch-body)])

(defmacro throw+
  "Like the throw special form, but can throw any object.
  See also try+"
  [obj]
  `(let [env# (zipmap '~(keys &env) [~@(keys &env)])]
     (throw (Stone.
             {:obj ~obj
              :env (dissoc env# '~'&throw-context)
              :next (env# '~'&throw-context)}))))

(defmacro try+
  "Like the try special form, but with enhanced catch clauses:
    - specify objects to catch by class, predicate, or type specifier;
    - destructure the caught object;
    - access the dynamic context at the throw site via the
      &throw-context hidden argument.

  A type-specifier is a map with one entry:
    - the key is the hierarchy (or nil for the global hierarchy);
    - the value is the type tag: a keyword or symbol.

  &throw-context is a map with keys:
    :obj the thrown object;
    :env a map of bound symbols to their values;
    :stack the stack trace;
    :next the next throw context in the cause chain.

  See also throw+"
  [& body]
  (let [[exprs catch-clauses finally-clause] (partition-body body)]
    `(try
       ~@exprs
       ~@(when catch-clauses
           `((catch Throwable ~'&throw-context
               (let [~'&throw-context
                     (with-meta
                       (assoc
                           (if (instance? Stone ~'&throw-context)
                             (.data ~'&throw-context)
                             {:obj ~'&throw-context})
                         :stack (.getStackTrace ~'&throw-context))
                       {:throwable ~'&throw-context})]
                 (cond
                  ~@(mapcat cond-clause catch-clauses)
                  :else
                  (throw (:throwable (meta ~'&throw-context))))))))
       ~@finally-clause)))
