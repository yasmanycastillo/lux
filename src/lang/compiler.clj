(ns lang.compiler
  (:refer-clojure :exclude [compile])
  (:require [clojure.string :as string]
            [clojure.core.match :refer [match]]
            (lang [type :as &type]
                  [lexer :as &lexer]
                  [parser :as &parser]
                  [analyser :as &analyser])
            :reload)
  (:import (org.objectweb.asm Opcodes
                              Label
                              ClassWriter
                              MethodVisitor)))

(declare compile-form
         compile)

;; [Utils/General]
(defn ^:private write-file [file data]
  (with-open [stream (java.io.BufferedOutputStream. (java.io.FileOutputStream. file))]
    (.write stream data)))

(def ^:private +variant-class+ "test2.Tagged")

(defmacro ^:private defcompiler [name match body]
  `(defn ~name [~'*state*]
     (let [~'*class-name* (:class-name ~'*state*)
           ~'*writer* (:writer ~'*state*)
           ~'*parent* (:parent ~'*state*)
           ~'*type* (:type (:form ~'*state*))]
       (match (:form (:form ~'*state*))
         ~match
         (do ~body
           true)
         _#
         false))))

(defn ^:private unwrap-ident [ident]
  (match ident
    [::&parser/ident ?label]
    ?label))

(defn ^:private unwrap-tagged [ident]
  (match ident
    [::&parser/tagged ?tag ?data]
    [?tag ?data]))

(defn ^:private ->class [class]
  (string/replace class #"\." "/"))

(def ^:private ->package ->class)

(defn ^:private ->type-signature [class]
  (case class
    "Void" "V"
    ;; else
    (str "L" (->class class) ";")))

(defn ^:private ->java-sig [type]
  (match type
    [::&type/object ?name []]
    (->type-signature ?name)

    [::&type/variant ?tag ?value]
    (->type-signature +variant-class+)

    [::&type/function ?args ?return]
    (->java-sig [::&type/object "test2/Function" []])))

;; [Utils/Compilers]
(defcompiler ^:private compile-literal
  [::&analyser/literal ?literal]
  (cond (instance? java.lang.Integer ?literal)
        (doto *writer*
          (.visitTypeInsn Opcodes/NEW (->class "java.lang.Integer"))
          (.visitInsn Opcodes/DUP)
          (.visitLdcInsn ?literal)
          (.visitMethodInsn Opcodes/INVOKESPECIAL (->class "java.lang.Integer") "<init>" "(I)V"))

        (instance? java.lang.Float ?literal)
        (doto *writer*
          (.visitTypeInsn Opcodes/NEW (->class "java.lang.Float"))
          (.visitInsn Opcodes/DUP)
          (.visitLdcInsn ?literal)
          (.visitMethodInsn Opcodes/INVOKESPECIAL (->class "java.lang.Float") "<init>" "(F)V"))

        (instance? java.lang.Character ?literal)
        (doto *writer*
          (.visitTypeInsn Opcodes/NEW (->class "java.lang.Character"))
          (.visitInsn Opcodes/DUP)
          (.visitLdcInsn ?literal)
          (.visitMethodInsn Opcodes/INVOKESPECIAL (->class "java.lang.Character") "<init>" "(C)V"))

        (instance? java.lang.Boolean ?literal)
        (if ?literal
          ;; (.visitLdcInsn *writer* (int 1))
          (.visitFieldInsn *writer* Opcodes/GETSTATIC (->class "java.lang.Boolean") "TRUE" (->type-signature "java.lang.Boolean"))
          ;; (.visitLdcInsn *writer* (int 0))
          (.visitFieldInsn *writer* Opcodes/GETSTATIC (->class "java.lang.Boolean") "FALSE" (->type-signature "java.lang.Boolean")))

        (string? ?literal)
        (.visitLdcInsn *writer* ?literal)

        :else
        (assert false (str "[Unknown literal type] " ?literal " : " (class ?literal)))))

(defcompiler ^:private compile-tuple
  [::&analyser/tuple ?elems]
  (let [num-elems (count ?elems)]
    (let [tuple-class (str "test2/Tuple" num-elems)]
      (doto *writer*
        (.visitTypeInsn Opcodes/NEW tuple-class)
        (.visitInsn Opcodes/DUP)
        (.visitMethodInsn Opcodes/INVOKESPECIAL tuple-class "<init>" "()V"))
      (dotimes [idx num-elems]
        (.visitInsn *writer* Opcodes/DUP)
        (compile-form (assoc *state* :form (nth ?elems idx)))
        (.visitFieldInsn *writer* Opcodes/PUTFIELD tuple-class (str "_" idx) "Ljava/lang/Object;")))))

(defcompiler ^:private compile-local
  [::&analyser/local ?idx]
  (do ;; (prn 'LOCAL ?idx)
      (doto *writer*
        (.visitVarInsn Opcodes/ALOAD (int ?idx)))))

(defcompiler ^:private compile-global
  [::&analyser/global ?owner-class ?name]
  (do ;; (prn 'GLOBAL ?owner-class ?name *type*)
      (doto *writer*
        (.visitFieldInsn Opcodes/GETSTATIC (->class ?owner-class) ?name (->java-sig *type*)))))

;; (defcompiler ^:private compile-call
;;   [::&analyser/call ?fn ?args]
;;   (do (prn 'compile-call (:form ?fn) ?fn ?args)
;;     (doseq [arg (reverse ?args)]
;;       (compile-form (assoc *state* :form arg)))
;;     (match (:form ?fn)
;;       [::&analyser/global ?owner-class ?fn-name]
;;       (let [signature (str "(" (apply str (repeat (count ?args) "Ljava/lang/Object;")) ")" "Ljava/lang/Object;")]
;;         (doto *writer*
;;           (.visitMethodInsn Opcodes/INVOKESTATIC (->class ?owner-class) ?fn-name signature))))))

(defcompiler ^:private compile-call
  [::&analyser/call ?fn ?args]
  (do ;; (prn 'compile-call (:form ?fn) ?fn ?args)
      (match (:form ?fn)
        [::&analyser/global ?owner-class ?fn-name]
        (let [apply-signature "(Ljava/lang/Object;)Ljava/lang/Object;"
              clo-field-sig (->type-signature "java.lang.Object")
              counter-sig "I"
              num-args (count ?args)
              signature (if (> (count ?args) 1)
                          (str "(" (apply str counter-sig (repeat (dec num-args) clo-field-sig)) ")" "V")
                          (str "()" "V"))
              call-class (str (->class ?owner-class) "$" ?fn-name)]
          (doto *writer*
            (.visitTypeInsn Opcodes/NEW call-class)
            (.visitInsn Opcodes/DUP)
            (-> (doto (.visitLdcInsn (-> ?args count dec int))
                  ;; (.visitInsn Opcodes/ICONST_0)
                  (-> (do (compile-form (assoc *state* :form arg)))
                      (->> (doseq [arg (butlast ?args)]))))
                (->> (when (> (count ?args) 1))))
            (.visitMethodInsn Opcodes/INVOKESPECIAL call-class "<init>" signature)
            (do (compile-form (assoc *state* :form (last ?args))))
            (.visitMethodInsn Opcodes/INVOKEINTERFACE "test2/Function" "apply" apply-signature))
          ;; (doseq [arg ?args]
          ;;   (compile-form (assoc *state* :form arg))
          ;;   (.visitMethodInsn *writer* Opcodes/INVOKEINTERFACE "test2/Function" "apply" apply-signature))
          )
        
        _
        (do (compile-form (assoc *state* :form ?fn))
          (let [apply-signature "(Ljava/lang/Object;)Ljava/lang/Object;"]
            (doseq [arg ?args]
              (compile-form (assoc *state* :form arg))
              (.visitMethodInsn *writer* Opcodes/INVOKEINTERFACE "test2/Function" "apply" apply-signature))))
        )))

(defcompiler ^:private compile-static-access
  [::&analyser/static-access ?class ?member]
  (doto *writer*
    (.visitFieldInsn Opcodes/GETSTATIC (->class ?class) ?member (->type-signature "java.io.PrintStream"))))

(defcompiler ^:private compile-dynamic-access
  [::&analyser/dynamic-access ?object [?method ?args]]
  (do (compile-form (assoc *state* :form ?object))
    (doseq [arg ?args]
      (compile-form (assoc *state* :form arg)))
    (doto *writer*
      (.visitMethodInsn Opcodes/INVOKEVIRTUAL (->class "java.io.PrintStream") ?method "(Ljava/lang/Object;)V")
      (.visitInsn Opcodes/ACONST_NULL))))

(defcompiler ^:private compile-ann-class
  [::&analyser/ann-class ?class ?members]
  nil)

(defcompiler ^:private compile-if
  [::&analyser/if ?test ?then ?else]
  (let [else-label (new Label)
        end-label (new Label)]
    ;; (println "PRE")
    (assert (compile-form (assoc *state* :form ?test)) "CAN't COMPILE TEST")
    (doto *writer*
      (.visitMethodInsn Opcodes/INVOKEVIRTUAL (->class "java.lang.Boolean") "booleanValue" "()Z")
      (.visitJumpInsn Opcodes/IFEQ else-label))
    ;; (prn 'compile-if/?then (:form ?then))
    (assert (compile-form (assoc *state* :form ?then)) "CAN't COMPILE THEN")
    (doto *writer*
      (.visitJumpInsn Opcodes/GOTO end-label)
      (.visitLabel else-label))
    (assert (compile-form (assoc *state* :form ?else)) "CAN't COMPILE ELSE")
    (.visitLabel *writer* end-label)))

(defcompiler ^:private compile-do
  [::&analyser/do ?exprs]
  (do (doseq [expr (butlast ?exprs)]
        (compile-form (assoc *state* :form expr))
        (.visitInsn *writer* Opcodes/POP))
    (compile-form (assoc *state* :form (last ?exprs)))))

(let [oclass (->class "java.lang.Object")
      equals-sig (str "(" (->type-signature "java.lang.Object") ")Z")]
  (defcompiler ^:private compile-case
    [::&analyser/case ?variant ?branches]
    (do (compile-form (assoc *state* :form ?variant))
      (let [end-label (new Label)]
        (doseq [[?tag ?label ?idx ?body] ?branches]
          ;; (prn '[?tag ?label ?idx ?body] [?tag ?label ?idx ?body])
          (let [else-label (new Label)]
            (doto *writer*
              (.visitInsn Opcodes/DUP)
              (.visitFieldInsn Opcodes/GETFIELD (->class +variant-class+) "tag" "Ljava/lang/String;")
              (.visitLdcInsn ?tag)
              (.visitMethodInsn Opcodes/INVOKEVIRTUAL oclass "equals" equals-sig)
              (.visitJumpInsn Opcodes/IFEQ else-label))
            (let [start-label (new Label)
                  end-label (new Label)]
              (doto *writer*
                (.visitInsn Opcodes/DUP)
                (.visitFieldInsn Opcodes/GETFIELD (->class +variant-class+) "value" (->type-signature "java.lang.Object")))
              (.visitLocalVariable *writer* ?label (->type-signature "java.lang.Object") nil start-label end-label ?idx)
              (doto *writer*
                (.visitVarInsn Opcodes/ASTORE ?idx)
                (.visitLabel start-label)
                (.visitInsn Opcodes/POP))
              (compile-form (assoc *state* :form ?body))
              (.visitLabel *writer* end-label))
            (doto *writer*
              (.visitJumpInsn Opcodes/GOTO end-label)
              (.visitLabel else-label))))
        (.visitLabel *writer* end-label))
      )))

(defcompiler ^:private compile-let
  [::&analyser/let ?idx ?label ?value ?body]
  (let [start-label (new Label)
        end-label (new Label)
        ?idx (int ?idx)]
    ;; (prn '(:type ?value) (:type ?value) (->java-sig (:type ?value)))
    (.visitLocalVariable *writer* ?label (->java-sig (:type ?value)) nil start-label end-label ?idx)
    (assert (compile-form (assoc *state* :form ?value)) "CAN't COMPILE LET-VALUE")
    (doto *writer*
      (.visitVarInsn Opcodes/ASTORE ?idx)
      (.visitLabel start-label))
    (assert (compile-form (assoc *state* :form ?body)) "CAN't COMPILE LET-BODY")
    (.visitLabel *writer* end-label)))

(defn ^:private compile-method-function [writer class-name fn-name num-args body *state*]
  (let [outer-class (->class class-name)
        clo-field-sig (->type-signature "java.lang.Object")
        counter-sig "I"
        apply-signature "(Ljava/lang/Object;)Ljava/lang/Object;"
        real-signature (str "(" (apply str (repeat num-args clo-field-sig)) ")" "Ljava/lang/Object;")
        current-class (str outer-class "$" fn-name)
        num-captured (dec num-args)
        init-signature (if (not= 0 num-captured)
                         (str "(" (apply str counter-sig (repeat num-captured clo-field-sig)) ")" "V")
                         (str "()" "V"))]
    ;; (.visitInnerClass writer current-class outer-class nil (+ Opcodes/ACC_STATIC Opcodes/ACC_SYNTHETIC))
    (let [=class (doto (new ClassWriter ClassWriter/COMPUTE_MAXS)
                   (.visit Opcodes/V1_5 (+ Opcodes/ACC_PUBLIC Opcodes/ACC_FINAL Opcodes/ACC_SUPER)
                           current-class nil "java/lang/Object" (into-array ["test2/Function"]))
                   (-> (doto (.visitField (+ Opcodes/ACC_PRIVATE Opcodes/ACC_FINAL) "_counter" counter-sig nil nil)
                         (.visitEnd))
                       (->> (when (not= 0 num-captured)))))
          =impl (doto (.visitMethod =class (+ Opcodes/ACC_PUBLIC Opcodes/ACC_STATIC) "impl" real-signature nil nil)
                  (.visitCode)
                  (->> (assoc *state* :form body :writer) compile-form)
                  (.visitInsn Opcodes/ARETURN)
                  (.visitMaxs 0 0)
                  (.visitEnd))
          =init (doto (.visitMethod =class Opcodes/ACC_PUBLIC "<init>" init-signature nil nil)
                  (.visitCode)
                  (.visitVarInsn Opcodes/ALOAD 0)
                  (.visitMethodInsn Opcodes/INVOKESPECIAL "java/lang/Object" "<init>" "()V")
                  (-> (doto ;; (.visitFieldInsn Opcodes/GETSTATIC (->class "java.lang.System") "out" (->type-signature "java.io.PrintStream"))
                          ;; (.visitTypeInsn Opcodes/NEW (->class "java.lang.Integer"))
                          ;; (.visitInsn Opcodes/DUP)
                          ;; (.visitVarInsn Opcodes/ALOAD 1)
                          ;; ;; (.visitVarInsn Opcodes/ALOAD 0)
                          ;; ;; (.visitFieldInsn Opcodes/GETFIELD current-class "_counter" counter-sig)
                          ;; (.visitMethodInsn Opcodes/INVOKESPECIAL (->class "java.lang.Integer") "<init>" "(I)V")
                          ;; (.visitMethodInsn Opcodes/INVOKEVIRTUAL (->class "java.io.PrintStream") "println" "(Ljava/lang/Object;)V")

                          (.visitVarInsn Opcodes/ALOAD 0)
                          (.visitVarInsn Opcodes/ILOAD 1)
                        ;; (.visitInsn Opcodes/ICONST_0)
                        
                          (.visitFieldInsn Opcodes/PUTFIELD current-class "_counter" counter-sig)
                        (-> (doto (.visitVarInsn Opcodes/ALOAD 0)
                              (.visitVarInsn Opcodes/ALOAD (+ clo_idx 2))
                              (.visitFieldInsn Opcodes/PUTFIELD current-class field-name clo-field-sig))
                            (->> (let [field-name (str "_" clo_idx)]
                                   (doto (.visitField =class (+ Opcodes/ACC_PRIVATE Opcodes/ACC_FINAL) field-name clo-field-sig nil nil)
                                     (.visitEnd)))
                                 (dotimes [clo_idx num-captured]))))
                      (->> (when (not= 0 num-captured))))
                  (.visitInsn Opcodes/RETURN)
                  (.visitMaxs 0 0)
                  (.visitEnd))
          =method (let [default-label (new Label)
                        branch-labels (for [_ (range num-captured)]
                                        (new Label))
                        ;; _ (prn 'branch-labels (count branch-labels) branch-labels)
                        ;; end-label (new Label)
                        ]
                    (doto (.visitMethod =class Opcodes/ACC_PUBLIC "apply" apply-signature nil nil)
                      (.visitCode)
                      (-> (doto ;; (.visitFieldInsn Opcodes/GETSTATIC (->class "java.lang.System") "out" (->type-signature "java.io.PrintStream"))
                              ;; (.visitTypeInsn Opcodes/NEW (->class "java.lang.Integer"))
                              ;; (.visitInsn Opcodes/DUP)
                              ;; (.visitVarInsn Opcodes/ALOAD 0)
                              ;; (.visitFieldInsn Opcodes/GETFIELD current-class "_counter" counter-sig)
                              ;; (.visitMethodInsn Opcodes/INVOKESPECIAL (->class "java.lang.Integer") "<init>" "(I)V")
                              ;; (.visitMethodInsn Opcodes/INVOKEVIRTUAL (->class "java.io.PrintStream") "println" "(Ljava/lang/Object;)V")
                              (.visitVarInsn Opcodes/ALOAD 0)
                            (.visitFieldInsn Opcodes/GETFIELD current-class "_counter" counter-sig)
                            (.visitTableSwitchInsn 0 (dec num-captured) default-label (into-array Label branch-labels))
                            (-> (doto (.visitLabel branch-label)
                                  (.visitTypeInsn Opcodes/NEW current-class)
                                  (.visitInsn Opcodes/DUP)
                                  (.visitVarInsn Opcodes/ALOAD 0)
                                  (.visitFieldInsn Opcodes/GETFIELD current-class "_counter" counter-sig)
                                  (.visitInsn Opcodes/ICONST_1)
                                  (.visitInsn Opcodes/IADD)
                                  (-> (doto (.visitVarInsn Opcodes/ALOAD 0)
                                        (.visitFieldInsn Opcodes/GETFIELD current-class (str "_" clo_idx) clo-field-sig))
                                      (->> (dotimes [clo_idx current-captured])))
                                  (.visitVarInsn Opcodes/ALOAD 1)
                                  (-> (.visitInsn Opcodes/ACONST_NULL)
                                      (->> (dotimes [clo_idx (- (dec num-captured) current-captured)])))
                                  (.visitMethodInsn Opcodes/INVOKESPECIAL current-class "<init>" init-signature)
                                  ;; (.visitJumpInsn Opcodes/GOTO end-label)
                                  (.visitInsn Opcodes/ARETURN))
                                (->> (doseq [[branch-label current-captured] (map vector branch-labels (range (count branch-labels)))
                                             ;; :let [_ (prn '[branch-label current-captured] [branch-label current-captured])]
                                             ])))
                            (.visitLabel default-label)
                            (-> (doto (.visitVarInsn Opcodes/ALOAD 0)
                                  (.visitFieldInsn Opcodes/GETFIELD current-class (str "_" clo_idx) clo-field-sig))
                                (->> (dotimes [clo_idx num-captured]))))
                          (->> (when (not= 0 num-captured))))
                      (.visitVarInsn Opcodes/ALOAD 1)
                      (.visitMethodInsn Opcodes/INVOKESTATIC current-class "impl" real-signature)
                      ;; (.visitLabel end-label)
                      (.visitInsn Opcodes/ARETURN)
                      (.visitMaxs 0 0)
                      (.visitEnd)))
          _ (.visitEnd =class)]
      (write-file (str current-class ".class") (.toByteArray =class)))
    ))

(defcompiler ^:private compile-def
  [::&analyser/def ?form ?body]
  (do ;; (prn 'compile-def ?form)
      (match ?form
        (?name :guard string?)
        (let [=type (:type ?body)
              ;; _ (prn '?body ?body)
              ]
          (doto (.visitField *writer* (+ Opcodes/ACC_PUBLIC Opcodes/ACC_STATIC) ?name (->java-sig =type) nil nil)
            (.visitEnd)))

        [?name ?args]
        (do ;; (prn 'compile-def `(~'def (~(symbol ?name) ~@(map symbol ?args))))
            (if (= "main" ?name)
              (let [signature "([Ljava/lang/String;)V"
                    =method (doto (.visitMethod *writer* (+ Opcodes/ACC_PUBLIC Opcodes/ACC_STATIC) ?name signature nil nil)
                              (.visitCode))]
                ;; (prn 'FN/?body ?body)
                (assert (compile-form (assoc *state* :parent *writer* :writer =method :form ?body)) (str "Body couldn't compile: " (pr-str ?body)))
                (doto =method
                  (.visitInsn Opcodes/RETURN)
                  (.visitMaxs 0 0)
                  (.visitEnd)))
              ;; (let [signature (str "(" (apply str (repeat (count ?args) "Ljava/lang/Object;")) ")" "Ljava/lang/Object;")
              ;;       ;; _ (prn 'signature signature)
              ;;       =method (doto (.visitMethod *writer* (+ Opcodes/ACC_PUBLIC Opcodes/ACC_STATIC) ?name signature nil nil)
              ;;                 (.visitCode))]
              ;;   (compile-form (assoc *state* :parent *writer* :writer =method :form ?body))
              ;;   (doto =method
              ;;     (.visitInsn Opcodes/ARETURN)
              ;;     (.visitMaxs 0 0)
              ;;     (.visitEnd))
              ;;   (compile-method-function *writer* *class-name* ?name (count ?args) ?body))
              (compile-method-function *writer* *class-name* ?name (count ?args) ?body *state*)))
        )))

(defcompiler ^:private compile-lambda
  [::&analyser/lambda ?args ?body]
  (let [num-args (count ?args)
        outer-class (->class *class-name*)
        clo-field-sig (->type-signature "java.lang.Object")
        counter-sig "I"
        apply-signature "(Ljava/lang/Object;)Ljava/lang/Object;"
        real-signature (str "(" (apply str (repeat num-args clo-field-sig)) ")" "Ljava/lang/Object;")
        current-class (str outer-class "$" "lambda")
        num-captured (dec num-args)
        init-signature (if (not= 0 num-captured)
                         (str "(" (apply str counter-sig (repeat num-captured clo-field-sig)) ")" "V")
                         (str "()" "V"))]
    ;; (.visitInnerClass *parent* current-class outer-class nil (+ Opcodes/ACC_STATIC Opcodes/ACC_SYNTHETIC))
    (let [=class (doto (new ClassWriter ClassWriter/COMPUTE_MAXS)
                   (.visit Opcodes/V1_5 (+ Opcodes/ACC_PUBLIC Opcodes/ACC_FINAL Opcodes/ACC_SUPER)
                           current-class nil "java/lang/Object" (into-array ["test2/Function"]))
                   (-> (doto (.visitField (+ Opcodes/ACC_PRIVATE Opcodes/ACC_FINAL) "_counter" counter-sig nil nil)
                         (.visitEnd))
                       (->> (when (not= 0 num-captured)))))
          =init (doto (.visitMethod =class Opcodes/ACC_PUBLIC "<init>" init-signature nil nil)
                  (.visitCode)
                  (.visitVarInsn Opcodes/ALOAD 0)
                  (.visitMethodInsn Opcodes/INVOKESPECIAL "java/lang/Object" "<init>" "()V")
                  (-> (doto (.visitVarInsn Opcodes/ALOAD 0)
                        (.visitVarInsn Opcodes/ILOAD 1)
                        (.visitFieldInsn Opcodes/PUTFIELD current-class "_counter" counter-sig)
                        (-> (doto (.visitVarInsn Opcodes/ALOAD 0)
                              (.visitVarInsn Opcodes/ALOAD (+ clo_idx 2))
                              (.visitFieldInsn Opcodes/PUTFIELD current-class field-name clo-field-sig))
                            (->> (let [field-name (str "_" clo_idx)]
                                   (doto (.visitField =class (+ Opcodes/ACC_PRIVATE Opcodes/ACC_FINAL) field-name clo-field-sig nil nil)
                                     (.visitEnd)))
                                 (dotimes [clo_idx num-captured]))))
                      (->> (when (not= 0 num-captured))))
                  (.visitInsn Opcodes/RETURN)
                  (.visitMaxs 0 0)
                  (.visitEnd))
          =method (let [default-label (new Label)
                        branch-labels (for [_ (range num-captured)]
                                        (new Label))]
                    (doto (.visitMethod =class Opcodes/ACC_PUBLIC "apply" apply-signature nil nil)
                      (.visitCode)
                      (-> (doto (.visitVarInsn Opcodes/ALOAD 0)
                            (.visitFieldInsn Opcodes/GETFIELD current-class "_counter" counter-sig)
                            (.visitTableSwitchInsn 0 (dec num-captured) default-label (into-array Label branch-labels))
                            (-> (doto (.visitLabel branch-label)
                                  (.visitTypeInsn Opcodes/NEW current-class)
                                  (.visitInsn Opcodes/DUP)
                                  (.visitVarInsn Opcodes/ALOAD 0)
                                  (.visitFieldInsn Opcodes/GETFIELD current-class "_counter" counter-sig)
                                  (.visitInsn Opcodes/ICONST_1)
                                  (.visitInsn Opcodes/IADD)
                                  (-> (doto (.visitVarInsn Opcodes/ALOAD 0)
                                        (.visitFieldInsn Opcodes/GETFIELD current-class (str "_" clo_idx) clo-field-sig))
                                      (->> (dotimes [clo_idx current-captured])))
                                  (.visitVarInsn Opcodes/ALOAD 1)
                                  (-> (.visitInsn Opcodes/ACONST_NULL)
                                      (->> (dotimes [clo_idx (- (dec num-captured) current-captured)])))
                                  (.visitMethodInsn Opcodes/INVOKESPECIAL current-class "<init>" init-signature)
                                  ;; (.visitJumpInsn Opcodes/GOTO end-label)
                                  (.visitInsn Opcodes/ARETURN))
                                (->> (doseq [[branch-label current-captured] (map vector branch-labels (range (count branch-labels)))
                                             ;; :let [_ (prn '[branch-label current-captured] [branch-label current-captured])]
                                             ])))
                            (.visitLabel default-label)
                            (-> (doto (.visitVarInsn Opcodes/ALOAD 0)
                                  (.visitFieldInsn Opcodes/GETFIELD current-class (str "_" clo_idx) clo-field-sig))
                                (->> (dotimes [clo_idx num-captured]))))
                          (->> (when (not= 0 num-captured))))
                      (.visitVarInsn Opcodes/ALOAD 1)
                      (.visitMethodInsn Opcodes/INVOKESTATIC current-class "impl" real-signature)
                      ;; (.visitLabel end-label)
                      (.visitInsn Opcodes/ARETURN)
                      (.visitMaxs 0 0)
                      (.visitEnd)))
          =impl (doto (.visitMethod =class (+ Opcodes/ACC_PUBLIC Opcodes/ACC_STATIC) "impl" real-signature nil nil)
                  (.visitCode)
                  (->> (assoc *state* :form ?body :writer)
                       compile-form)
                  (.visitInsn Opcodes/ARETURN)
                  (.visitMaxs 0 0)
                  (.visitEnd))
          _ (.visitEnd =class)]
      (write-file (str current-class ".class") (.toByteArray =class)))
    (doto *writer*
      (.visitTypeInsn Opcodes/NEW current-class)
      (.visitInsn Opcodes/DUP)
      (-> (doto (.visitInsn Opcodes/ICONST_0)
            ;; (.visitInsn Opcodes/ICONST_0)
            (-> (.visitInsn Opcodes/ACONST_NULL)
                (->> (doseq [_ (butlast ?args)]))))
          (->> (when (> (count ?args) 1))))
      (.visitMethodInsn Opcodes/INVOKESPECIAL current-class "<init>" init-signature))
    ))

(defcompiler ^:private compile-defclass
  [::&analyser/defclass [?package ?name] ?members]
  (let [parent-dir (->package ?package)
        =class (doto (new ClassWriter ClassWriter/COMPUTE_MAXS)
                 (.visit Opcodes/V1_5 (+ Opcodes/ACC_PUBLIC Opcodes/ACC_SUPER)
                         (str parent-dir "/" ?name) nil "java/lang/Object" nil))]
    (doseq [[field props] (:fields ?members)]
      (doto (.visitField =class Opcodes/ACC_PUBLIC field (->type-signature (:type props)) nil nil)
        (.visitEnd)))
    (doto (.visitMethod =class Opcodes/ACC_PUBLIC "<init>" "()V" nil nil)
      (.visitCode)
      (.visitVarInsn Opcodes/ALOAD 0)
      (.visitMethodInsn Opcodes/INVOKESPECIAL "java/lang/Object" "<init>" "()V")
      (.visitInsn Opcodes/RETURN)
      (.visitMaxs 0 0)
      (.visitEnd))
    (.visitEnd =class)
    (.mkdirs (java.io.File. parent-dir))
    (with-open [stream (java.io.BufferedOutputStream. (java.io.FileOutputStream. (str parent-dir "/" ?name ".class")))]
      (.write stream (.toByteArray =class)))))

(defcompiler ^:private compile-definterface
  [::&analyser/definterface [?package ?name] ?members]
  (let [parent-dir (->package ?package)
        =interface (doto (new ClassWriter ClassWriter/COMPUTE_MAXS)
                     (.visit Opcodes/V1_5 (+ Opcodes/ACC_PUBLIC Opcodes/ACC_INTERFACE ;; Opcodes/ACC_ABSTRACT
                                             )
                             (str parent-dir "/" ?name) nil "java/lang/Object" nil))]
    (doseq [[?method ?props] (:methods ?members)
            :let [[?args ?return] (:type ?props)
                  signature (str "(" (reduce str "" (map ->type-signature ?args)) ")" (->type-signature ?return))]]
      (.visitMethod =interface (+ Opcodes/ACC_PUBLIC Opcodes/ACC_ABSTRACT) ?method signature nil nil))
    (.visitEnd =interface)
    (.mkdirs (java.io.File. parent-dir))
    (with-open [stream (java.io.BufferedOutputStream. (java.io.FileOutputStream. (str parent-dir "/" ?name ".class")))]
      (.write stream (.toByteArray =interface)))))

(defcompiler ^:private compile-variant
  [::&analyser/variant ?tag ?value]
  (let [variant-class* (->class +variant-class+)]
    ;; (prn 'compile-variant ?tag ?value)
    (doto *writer*
      (.visitTypeInsn Opcodes/NEW variant-class*)
      (.visitInsn Opcodes/DUP)
      (.visitMethodInsn Opcodes/INVOKESPECIAL variant-class* "<init>" "()V")
      (.visitInsn Opcodes/DUP)
      (.visitLdcInsn ?tag)
      (.visitFieldInsn Opcodes/PUTFIELD variant-class* "tag" "Ljava/lang/String;")
      (.visitInsn Opcodes/DUP))
    (assert (compile-form (assoc *state* :form ?value)) (pr-str "Can't compile value: " ?value))
    (doto *writer*
      (.visitFieldInsn Opcodes/PUTFIELD variant-class* "value" "Ljava/lang/Object;"))
    ))

(defcompiler compile-import
  [::&analyser/import ?class]
  nil)

(defcompiler compile-require
  [::&analyser/require ?file ?alias]
  (let [module-name (re-find #"[^/]+$" ?file)
        ;; _ (prn 'module-name module-name)
        source-code (slurp (str module-name ".lang"))
        ;; _ (prn 'source-code source-code)
        tokens (&lexer/lex source-code)
        ;; _ (prn 'tokens tokens)
        syntax (&parser/parse tokens)
        ;; _ (prn 'syntax syntax)
        ann-syntax (&analyser/analyse module-name syntax)
        ;; _ (prn 'ann-syntax ann-syntax)
        class-data (compile module-name ann-syntax)]
    (write-file (str module-name ".class") class-data)
    nil))

(let [+compilers+ [compile-literal
                   compile-variant
                   compile-tuple
                   compile-local
                   compile-global
                   compile-call
                   compile-static-access
                   compile-dynamic-access
                   compile-ann-class
                   compile-if
                   compile-do
                   compile-case
                   compile-let
                   compile-lambda
                   compile-def
                   compile-defclass
                   compile-definterface
                   compile-import
                   compile-require]]
  (defn ^:private compile-form [state]
    ;; (prn 'compile-form/state state)
    (or (some #(% state) +compilers+)
        (assert false (str "Can't compile: " (pr-str (:form state)))))))

;; [Interface]
(defn compile [class-name inputs]
  ;; (prn 'inputs inputs)
  (let [=class (doto (new ClassWriter ClassWriter/COMPUTE_MAXS)
                 (.visit Opcodes/V1_5 (+ Opcodes/ACC_PUBLIC Opcodes/ACC_SUPER)
                         (->class class-name) nil "java/lang/Object" nil))
        ;; (doto (new ClassWriter ClassWriter/COMPUTE_MAXS)
        ;;          (.visit Opcodes/V1_5 (+ Opcodes/ACC_PUBLIC Opcodes/ACC_SUPER)
        ;;                  "output" nil "java/lang/Object" nil))
        state {:class-name class-name
               :writer =class
               :form nil
               :parent nil}]
    ;; (doto (.visitMethod =class Opcodes/ACC_PUBLIC "<init>" "()V" nil nil)
    ;;   (.visitCode)
    ;;   (.visitVarInsn Opcodes/ALOAD 0)
    ;;   (.visitMethodInsn Opcodes/INVOKESPECIAL "java/lang/Object" "<init>" "()V")
    ;;   (.visitInsn Opcodes/RETURN)
    ;;   (.visitMaxs 0 0)
    ;;   (.visitEnd))
    (doseq [input inputs]
      (when (not (compile-form (assoc state :form input)))
        (assert false input)))
    ;; (doall (map #(compile-form (assoc state :form %)) inputs))
    ;; (prn 'inputs inputs)
    (when-let [constants (seq (for [input inputs
                                    :let [payload (match (:form input)
                                                    [::&analyser/def (?name :guard string?) ?body]
                                                    [?name ?body]
                                                    _
                                                    nil)]
                                    :when payload]
                                payload))]
      (let [=init (doto (.visitMethod =class Opcodes/ACC_PUBLIC "<clinit>" "()V" nil nil)
                    (.visitCode))
            state* (assoc state :writer =init)
            class-name* (->class class-name)]
        (doseq [[?name ?body] constants]
          (do (assert (compile-form (assoc state* :form ?body)) (str "Couldn't compile init: " (pr-str ?body)))
            (.visitFieldInsn =init Opcodes/PUTSTATIC class-name* ?name (->java-sig (:type ?body)))))
        (doto =init
          (.visitInsn Opcodes/RETURN)
          (.visitMaxs 0 0)
          (.visitEnd))))
    (.visitEnd =class)
    (.toByteArray =class)))
