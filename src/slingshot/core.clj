(ns slingshot.core
  (:import (slingshot Stone)))

(defn- clause-type [x]
  (when (seq? x) (#{'catch 'finally} (first x))))

(defn- partition-body [body]
  (let [[e c f s] (partition-by clause-type body)
        [e c f s] (if (-> (first e) clause-type nil?) [e c f s] [nil e c f])
        [c f s] (if (-> (first c) clause-type (= 'catch)) [c f s] [nil c f])
        [f s] (if (-> (first f) clause-type (= 'finally)) [f s] [nil f])]
    (when (or s (> (count f) 1))
      (throw (IllegalArgumentException.
              (str "try+ form must match: "
                   "(try+ expr* catch-clause* finally-clause?)"))))
    [e c f]))

(defn- resolved [x]
  (when (symbol? x)
    (or (resolve x)
        (throw (IllegalArgumentException.
                (str "Unable to resolve symbol: " x " in this context"))))))

(defn- catch->cond [[_ selector binding-form & exprs]]
  [(cond (class? (resolved selector))
         `(instance? ~selector (:obj ~'&throw-context))
         (seq? selector)
         (clojure.walk/prewalk-replace
          {(-> *ns* ns-name name (symbol "%")) '(:obj &throw-context)}
          selector)
         :else
         `(~selector (:obj ~'&throw-context)))
   `(let [~binding-form (:obj ~'&throw-context)]
      ~@exprs)])

(defn- transform
  "Transform try+ catch-clauses and default into a try-compatible catch"
  [catch-clauses default]
  ;; the code below uses only one local to minimize clutter in the
  ;; &env captured by throw+ forms within catch clauses (see the
  ;; special handling of &throw-context in throw+)
  `(catch Throwable ~'&throw-context
     (let [~'&throw-context (-> ~'&throw-context throw-context *catch-hook*)]
       (cond
        (contains? (meta ~'&throw-context) :catch-hook-return)
        (:catch-hook-return (meta ~'&throw-context))
        (contains? (meta ~'&throw-context) :catch-hook-throw)
        (throw (:catch-hook-throw (meta ~'&throw-context)))
        ~@(mapcat catch->cond catch-clauses)
        :else ~default))))

(defn make-stack-trace
  "Returns the current stack trace beginning at the caller's frame"
  []
  (let [trace (.getStackTrace (Thread/currentThread))]
    (java.util.Arrays/copyOfRange trace 2 (count trace))))

(defn make-throwable
  "Returns a Throwable given message, cause, and context"
  [message cause context]
  (Stone. message cause context))

(defn format-message
  "Returns a message string given a context"
  [{:keys [msg obj]}]
  (str (or msg "Object thrown by throw+") ": " (pr-str obj)))

(defn default-throw-hook
  "Default implementation of *throw-hook*. If obj in context is a
  Throwable, throw it, else make a Throwable to carry it and throw
  that."
  [{:keys [obj cause] :as context}]
  (throw
   (if (instance? Throwable obj)
     obj
     (make-throwable (format-message context) cause context))))

(def ^{:dynamic true
       :doc "Hook to allow overriding the behavior of throw+. Must be
  bound to a function of one argument, a context map. Defaults to
  default-throw-hook"}
  *throw-hook* default-throw-hook)

(def ^{:dynamic true
       :doc "Hook to allow overriding the behavior of catch. Must be
  bound to a function of one argument, a context map with
  metadata. Returns a (possibly modified) context map to be considered
  by catch clauses. Existing metadata on the context map must be
  preserved (or intentionally modified) in the returned context map.

  Normal processing by catch clauses can be preempted by adding
  special keys to the metadata on the returned context map:

    - if the metadata contains the key :catch-hook-return, try+ will
      return the corresponding value; else

    - if the metadata contains the key :catch-hook-throw, try+ will throw
      the corresponding value.

  Defaults to identity."}
  *catch-hook* identity)

(defn throw-context
  "Returns the context map associated with t. If t or any throwable in
  its cause chain is a Stone, return its context, else return a new
  context with t as the thrown object."
  [t]
  (-> (loop [c t]
        (cond (instance? Stone c)
              (assoc (.context c) :wrapper t)
              (.getCause c)
              (recur (.getCause c))
              :else
              {:obj t
               :msg (.getMessage t)
               :cause (.getCause t)
               :stack (.getStackTrace t)}))
      (with-meta {:throwable t})))

(defmacro throw+
  "Like the throw special form, but can throw any object. Behaves the
  same as throw for Throwable objects. For other objects, an optional
  second argument specifies a message which by default is displayed
  along with the object's value if it is caught outside a try+
  form. Within a try+ catch clause, throw+ with no arguments rethrows
  the caught object.

  See also try+"
  ([obj & [msg sen]]
     (when sen
       (throw (IllegalArgumentException.
               "throw+ call must match: (throw+ obj? ^String msg?")))
     `(*throw-hook*
       (let [env# (zipmap '~(keys &env) [~@(keys &env)])]
         {:obj ~obj
          :msg ~msg
          :cause (-> (env# '~'&throw-context) meta :throwable)
          :stack (make-stack-trace)
          :env (dissoc env# '~'&throw-context)})))
  ([] `(throw (-> ~'&throw-context meta :throwable))))

(defmacro try+
  "Like the try special form, but with enhanced catch clauses:
    - specify objects to catch by classname, predicate, or
      selector form;
    - destructure the caught object;
    - access the dynamic context at the throw site via the
      &throw-context hidden argument.

  A selector form is a form containing one or more instances of % to
  be replaced by the thrown object. If it evaluates to truthy, the
  object is caught.

  Classname and predicate selectors are shorthand for these selector
  forms:

    <classname> => (instance? <classname> %)
    <predicate> => (<predicate> %)

  &throw-context is a map containing:
    - for all caught objects:
      :obj      the thrown object;
      :stack    the stack trace;
    - for Throwable caught objects
      :msg      the message, from .getMessage;
      :cause    the cause, from .getCause;
    - for non-Throwable caught objects:
      :msg      the message, from the optional argument to throw+;
      :cause    the cause, captured by throw+, see below;
      :wrapper  the outermost Throwable wrapper of the caught object,
                see below;
      :env      a map of bound symbols to their values.

  To throw a non-Throwable object, throw+ wraps it with an object of
  type Stone. That Stone in turn may end up being wrapped by other
  exceptions (e.g., instances of RuntimeException or
  java.util.concurrent.ExecutionException). try+ \"sees through\" all
  such wrappers to find the object wrapped by the first instance of
  Stone in the outermost wrapper's cause chain. If needed, the
  outermost wrapper is available within a catch clause at the :wrapper
  key in &throw-context. Any nested wrappers are accessible via its
  .getCause chain.

  When throw+ throws a non-Throwable object from within a try+ catch
  clause, the outermost wrapper of the caught object being processed
  is captured as the \"cause\" of the new throw.

  See also throw+"
  [& body]
  (let [[exprs catch-clauses finally-clause] (partition-body body)]
    `(try
       ~@exprs
       ~@(when catch-clauses
           [(transform catch-clauses '(throw+))])
       ~@finally-clause)))
