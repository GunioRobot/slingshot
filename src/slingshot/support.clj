(ns slingshot.support
  (:use [clojure.walk :only [prewalk-replace]])
  (:import slingshot.Stone))

;; try+ support

(defn throw-arg
  "Throws an IllegalArgumentException with a message. Args are like
  those of clojure.core/format."
  [fmt & args]
  (throw (IllegalArgumentException. (apply format fmt args))))

(defn clause-type
  "Returns a classifying value for any object in a try+ body:
  expr, catch-clause, or finally-clause"
  [x]
  (when (seq? x) (#{'catch 'finally} (first x))))

(defn partition-body
  "Partitions a try+ body into exprs, catch-clauses, finally clauses,
  and a sentinel which is nil when the body is well formed"
  [body]
  (let [[e c f s] (partition-by clause-type body)
        [e c f s] (if (-> (first e) clause-type nil?) [e c f s] [nil e c f])
        [c f s] (if (-> (first c) clause-type (= 'catch)) [c f s] [nil c f])
        [f s] (if (-> (first f) clause-type (= 'finally)) [f s] [nil f])
        s (or s (next f))]
    [e c f s]))

(defn parse
  "Returns parsed body parts for valid bodies or throws on syntax error"
  [body]
  (let [[exprs catch-clauses finally-clauses sentinel] (partition-body body)]
    (if sentinel
      (throw-arg "try+ form must match: (try+ %s)"
                 "expr* catch-clause* finally-clause?")
      [exprs catch-clauses finally-clauses])))

(defn resolved
  "For a symbol, returns the var or Class to which it will be resolved
  in the current namespace or throws if it could not be resolved"
  [x]
  (when (symbol? x)
    (or (resolve x)
        (throw-arg "Unable to resolve symbol: %s in this context" x))))

(defn ns-qualify
  "Returns a fully qualified symbol with the same name as the
  argument, but \"in\" the current namespace"
  [sym]
  (-> *ns* ns-name name (symbol (name sym))))

(defn catch->cond
  "Converts a try+ catch-clause into a test/expr pair for cond"
  [[_ selector binding-form & exprs]]
  [(cond (class? (resolved selector))
         `(instance? ~selector (:object ~'&throw-context))
         (vector? selector)
         (let [[key val & sentinel] selector]
           (if sentinel
             (throw-arg "key-value selector: %s does not match: [key val]"
                        (pr-str selector))
             `(= (get (:object ~'&throw-context) ~key) ~val)))
         (seq? selector)
         (prewalk-replace {(ns-qualify '%) '(:object &throw-context)} selector)
         :else ;; predicate
         `(~selector (:object ~'&throw-context)))
   `(let [~binding-form (:object ~'&throw-context)]
      ~@exprs)])

(defn throwable->context
  "Returns the context map associated with a Throwable t. If t or any
  Throwable in its cause chain is a Stone, returns its context, else
  returns a new context with t as the thrown object."
  [t]
  (-> (loop [c t]
        (cond (instance? Stone c)
              (assoc (.getContext c) :wrapper t)
              (.getCause c)
              (recur (.getCause c))
              :else
              {:object t
               :message (.getMessage t)
               :cause (.getCause t)
               :stack-trace (.getStackTrace t)}))
      (with-meta {:throwable t})))

(def ^{:dynamic true
       :doc "Hook to allow overriding the behavior of catch. Must be
  bound to a function of one argument, a context map with
  metadata. Returns a (possibly modified) context map to be considered
  by catch clauses. Existing metadata on the context map must be
  preserved (or intentionally modified) in the returned context map.

  Normal processing by catch clauses can be skipped by adding special
  keys to the metadata on the returned context map:

  If the metadata contains the key:
    - :catch-hook-return, try+ will return the corresponding value;
    - :catch-hook-throw, try+ will throw+ the corresponding value;
    - :catch-hook-rethrow, try+ will rethrow the caught object's
      outermost wrapper.

  Defaults to identity."}
  *catch-hook* identity)

(defn try-compatible-catch
  "Transforms a seq of try+ catch-clauses into a single try-compatible
  catch. throw-sym must name a macro or function that can accept zero
  or one arguments: one argument for :catch-hook-throw requests, and
  zero arguments for :catch-hook-rethrow requests or when no catch
  clause matches."
  [catch-clauses throw-sym]
  ;; the code below uses only one local to minimize clutter in the
  ;; &env captured by throw+ forms within catch clauses (see the
  ;; special handling of &throw-context in make-context)
  `(catch Throwable ~'&throw-context
     (let [~'&throw-context (-> ~'&throw-context throwable->context
                                *catch-hook*)]
       (cond
        (contains? (meta ~'&throw-context) :catch-hook-return)
        (:catch-hook-return (meta ~'&throw-context))
        (contains? (meta ~'&throw-context) :catch-hook-throw)
        (~throw-sym (:catch-hook-throw (meta ~'&throw-context)))
        (contains? (meta ~'&throw-context) :catch-hook-rethrow)
        (~throw-sym)
        ~@(mapcat catch->cond catch-clauses)
        :else
        (~throw-sym)))))

(defn transform
  "Returns a try-compatible catch if there are any catch clauses"
  [catch-clauses throw-sym]
  (when catch-clauses
    [(try-compatible-catch catch-clauses throw-sym)]))

;; throw+ support

(defn make-throwable
  "Returns a throwable Stone that wraps the given a message, cause,
  stack-trace, and context"
  [message cause stack-trace context]
  (Stone. message cause stack-trace context))

(defn context-message
  "Returns the default message string for a throw context"
  [{:keys [message object]}]
  (str message ": " (pr-str object)))

(defn context->throwable
  "If object in context is a Throwable, returns it, else wraps it and
   returns the wrapper."
  [{:keys [object cause stack-trace] :as context}]
  (if (instance? Throwable object)
    object
    (make-throwable (context-message context) cause stack-trace context)))

(defn default-throw-hook
  "Default implementation of *throw-hook*"
  [context]
  (throw (context->throwable context)))

(defn stack-trace
  "Returns the current stack trace beginning at the caller's frame"
  []
  (let [trace (.getStackTrace (Thread/currentThread))]
    (java.util.Arrays/copyOfRange trace 2 (alength trace))))

(defmacro env-map
  "Expands to code that generates a map of locals: names to values"
  []
  `(zipmap '~(keys &env) [~@(keys &env)]))

(defn rethrow
  "Rethrows the Throwable that try caught"
  [context]
  (throw (-> context meta :throwable)))

(defn make-context
  "Makes a throw context from arguments. Captures the cause if called
  within a catch clause."
  [object message stack-trace environment]
  {:object object
   :message message
   :cause (-> (environment '&throw-context) meta :throwable)
   :stack-trace stack-trace
   :environment (dissoc environment '&throw-context)})

(def ^{:dynamic true
       :doc "Hook to allow overriding the behavior of throw+. Must be
  bound to a function of one argument, a context map. Defaults to
  default-throw-hook."}
  *throw-hook* default-throw-hook)

(defn throw-context
  "Throws a context. Allows overrides of *throw-hook* to intervene."
  [object message stack-trace environment]
  (*throw-hook* (make-context object message stack-trace environment)))
