(ns lux.analyser.case
  (:require [clojure.core.match :as M :refer [match matchv]]
            clojure.core.match.array
            (lux [base :as & :refer [|do return fail |let]]
                 [parser :as &parser]
                 [type :as &type])
            (lux.analyser [base :as &&]
                          [env :as &env])))

;; [Utils]
(defn ^:private resolve-type [type]
  (matchv ::M/objects [type]
    [["lux;VarT" ?id]]
    (|do [type* (&/try-all% (&/|list (&type/deref ?id)
                                     (fail "##9##")))]
      (resolve-type type*))

    [_]
    (&type/actual-type type)))

(defn ^:private analyse-pattern [value-type pattern kont]
  ;; (prn 'analyse-pattern/pattern (aget pattern 0) (aget pattern 1) (alength (aget pattern 1)))
  (matchv ::M/objects [pattern]
    [["lux;Meta" [_ pattern*]]]
    ;; (assert false)
    (do ;; (prn 'analyse-pattern/pattern* (aget pattern* 0))
      (matchv ::M/objects [pattern*]
        [["lux;Symbol" ?ident]]
        (|do [=kont (&env/with-local (&/ident->text ?ident) value-type
                      kont)
              idx &env/next-local-idx]
          (return (&/T (&/V "StoreTestAC" idx) =kont)))

        [["lux;Bool" ?value]]
        (|do [_ (&type/check value-type &type/Bool)
              =kont kont]
          (return (&/T (&/V "BoolTestAC" ?value) =kont)))

        [["lux;Int" ?value]]
        (|do [_ (&type/check value-type &type/Int)
              =kont kont]
          (return (&/T (&/V "IntTestAC" ?value) =kont)))

        [["lux;Real" ?value]]
        (|do [_ (&type/check value-type &type/Real)
              =kont kont]
          (return (&/T (&/V "RealTestAC" ?value) =kont)))

        [["lux;Char" ?value]]
        (|do [_ (&type/check value-type &type/Char)
              =kont kont]
          (return (&/T (&/V "CharTestAC" ?value) =kont)))

        [["lux;Text" ?value]]
        (|do [_ (&type/check value-type &type/Text)
              =kont kont]
          (return (&/T (&/V "TextTestAC" ?value) =kont)))

        [["lux;Tuple" ?members]]
        (matchv ::M/objects [value-type]
          [["lux;TupleT" ?member-types]]
          (if (not (= (&/|length ?member-types) (&/|length ?members)))
            (fail (str "[Analyser error] Pattern-matching mismatch. Require tuple[" (&/|length ?member-types) "]. Given tuple [" (&/|length ?members) "]"))
            (|do [[=tests =kont] (&/fold (fn [kont* vm]
                                           (|let [[v m] vm]
                                             (|do [[=test [=tests =kont]] (analyse-pattern v m kont*)]
                                               (return (&/T (&/|cons =test =tests) =kont)))))
                                         (|do [=kont kont]
                                           (return (&/T (&/|list) =kont)))
                                         (&/|reverse (&/zip2 ?member-types ?members)))]
              (return (&/T (&/V "TupleTestAC" =tests) =kont))))

          [_]
          (fail "[Analyser Error] Tuple requires tuple-type."))

        [["lux;Record" ?slots]]
        (|do [value-type* (resolve-type value-type)]
          (matchv ::M/objects [value-type*]
            [["lux;RecordT" ?slot-types]]
            (if (not (= (&/|length ?slot-types) (&/|length ?slots)))
              (fail (str "[Analyser error] Pattern-matching mismatch. Require record[" (&/|length ?slot-types) "]. Given record[" (&/|length ?slots) "]"))
              (|do [[=tests =kont] (&/fold (fn [kont* slot]
                                             (|let [[sn sv] slot]
                                               (matchv ::M/objects [sn]
                                                 [["lux;Meta" [_ ["lux;Tag" ?ident]]]]
                                                 (|do [=tag (&&/resolved-ident ?ident)]
                                                   (if-let [=slot-type (&/|get =tag ?slot-types)]
                                                     (|do [[=test [=tests =kont]] (analyse-pattern =slot-type sv kont*)]
                                                       (return (&/T (&/|put =tag =test =tests) =kont)))
                                                     (fail (str "[Pattern-Matching Error] Record-type lacks slot: " =tag))))

                                                 [_]
                                                 (fail (str "[Pattern-Matching Error] Record must use tags as slot-names: " (&/show-ast sn))))))
                                           (|do [=kont kont]
                                             (return (&/T (&/|table) =kont)))
                                           (&/|reverse ?slots))]
                (return (&/T (&/V "RecordTestAC" =tests) =kont))))

            [_]
            (fail "[Analyser Error] Record requires record-type.")))

        [["lux;Tag" ?ident]]
        (|do [=tag (&&/resolved-ident ?ident)
              value-type* (resolve-type value-type)
              case-type (&type/variant-case =tag value-type*)
              [=test =kont] (analyse-pattern case-type (&/V "lux;Meta" (&/T (&/T "" -1 -1)
                                                                            (&/V "lux;Tuple" (&/|list))))
                                             kont)]
          (return (&/T (&/V "VariantTestAC" (&/T =tag =test)) =kont)))

        [["lux;Form" ["lux;Cons" [["lux;Meta" [_ ["lux;Tag" ?ident]]]
                                  ["lux;Cons" [?value
                                               ["lux;Nil" _]]]]]]]
        (|do [=tag (&&/resolved-ident ?ident)
              value-type* (resolve-type value-type)
              case-type (&type/variant-case =tag value-type*)
              [=test =kont] (analyse-pattern case-type ?value
                                             kont)]
          (return (&/T (&/V "VariantTestAC" (&/T =tag =test)) =kont)))
        ))))

(defn ^:private analyse-branch [analyse exo-type value-type pattern body patterns]
  (|do [pattern+body (analyse-pattern value-type pattern
                                      (&&/analyse-1 analyse exo-type body))]
    (return (&/|cons pattern+body patterns))))

(let [compare-kv #(.compareTo ^String (aget ^objects %1 0) ^String (aget ^objects %2 0))]
  (defn ^:private merge-total [struct test+body]
    (|let [[test ?body] test+body]
      (matchv ::M/objects [struct test]
        [["DefaultTotal" total?] ["StoreTestAC" ?idx]]
        (return (&/V "DefaultTotal" true))

        [[?tag [total? ?values]] ["StoreTestAC" ?idx]]
        (return (&/V ?tag (&/T true ?values)))
        
        [["DefaultTotal" total?] ["BoolTestAC" ?value]]
        (return (&/V "BoolTotal" (&/T total? (&/|list ?value))))

        [["BoolTotal" [total? ?values]] ["BoolTestAC" ?value]]
        (return (&/V "BoolTotal" (&/T total? (&/|cons ?value ?values))))

        [["DefaultTotal" total?] ["IntTestAC" ?value]]
        (return (&/V "IntTotal" (&/T total? (&/|list ?value))))

        [["IntTotal" [total? ?values]] ["IntTestAC" ?value]]
        (return (&/V "IntTotal" (&/T total? (&/|cons ?value ?values))))

        [["DefaultTotal" total?] ["RealTestAC" ?value]]
        (return (&/V "RealTotal" (&/T total? (&/|list ?value))))

        [["RealTotal" [total? ?values]] ["RealTestAC" ?value]]
        (return (&/V "RealTotal" (&/T total? (&/|cons ?value ?values))))

        [["DefaultTotal" total?] ["CharTestAC" ?value]]
        (return (&/V "CharTotal" (&/T total? (&/|list ?value))))

        [["CharTotal" [total? ?values]] ["CharTestAC" ?value]]
        (return (&/V "CharTotal" (&/T total? (&/|cons ?value ?values))))

        [["DefaultTotal" total?] ["TextTestAC" ?value]]
        (return (&/V "TextTotal" (&/T total? (&/|list ?value))))

        [["TextTotal" [total? ?values]] ["TextTestAC" ?value]]
        (return (&/V "TextTotal" (&/T total? (&/|cons ?value ?values))))

        [["DefaultTotal" total?] ["TupleTestAC" ?tests]]
        (|do [structs (&/map% (fn [t]
                                (merge-total (&/V "DefaultTotal" total?) (&/T t ?body)))
                              ?tests)]
          (return (&/V "TupleTotal" (&/T total? structs))))

        [["TupleTotal" [total? ?values]] ["TupleTestAC" ?tests]]
        (if (= (&/|length ?values) (&/|length ?tests))
          (|do [structs (&/map% (fn [vt]
                                  (|let [[v t] vt]
                                    (merge-total v (&/T t ?body))))
                                (&/zip2 ?values ?tests))]
            (return (&/V "TupleTotal" (&/T total? structs))))
          (fail "[Pattern-matching error] Inconsistent tuple-size."))

        [["DefaultTotal" total?] ["RecordTestAC" ?tests]]
        (|do [structs (&/map% (fn [t]
                                (|let [[slot value] t]
                                  (|do [struct* (merge-total (&/V "DefaultTotal" total?) (&/T value ?body))]
                                    (return (&/T slot struct*)))))
                              (->> ?tests
                                   &/->seq
                                   (sort compare-kv)
                                   &/->list))]
          (return (&/V "RecordTotal" (&/T total? structs))))

        [["RecordTotal" [total? ?values]] ["RecordTestAC" ?tests]]
        (if (= (&/|length ?values) (&/|length ?tests))
          (|do [structs (&/map% (fn [lr]
                                  (|let [[[lslot sub-struct] [rslot value]] lr]
                                    (if (= lslot rslot)
                                      (|do [sub-struct* (merge-total sub-struct (&/T value ?body))]
                                        (return (&/T lslot sub-struct*)))
                                      (fail "[Pattern-matching error] Record slots mismatch."))))
                                (&/zip2 ?values
                                        (->> ?tests
                                             &/->seq
                                             (sort compare-kv)
                                             &/->list)))]
            (return (&/V "RecordTotal" (&/T total? structs))))
          (fail "[Pattern-matching error] Inconsistent record-size."))

        [["DefaultTotal" total?] ["VariantTestAC" [?tag ?test]]]
        (|do [sub-struct (merge-total (&/V "DefaultTotal" total?)
                                      (&/T ?test ?body))]
          (return (&/V "VariantTotal" (&/T total? (&/|put ?tag sub-struct (&/|table))))))

        [["VariantTotal" [total? ?branches]] ["VariantTestAC" [?tag ?test]]]
        (|do [sub-struct (merge-total (or (&/|get ?tag ?branches)
                                          (&/V "DefaultTotal" total?))
                                      (&/T ?test ?body))]
          (return (&/V "VariantTotal" (&/T total? (&/|put ?tag sub-struct ?branches)))))
        ))))

(defn ^:private check-totality [value-type struct]
  ;; (prn 'check-totality (aget value-type 0) (aget struct 0) (&type/show-type value-type))
  (matchv ::M/objects [struct]
    [["BoolTotal" [?total ?values]]]
    (return (or ?total
                (= #{true false} (set (&/->seq ?values)))))

    [["IntTotal" [?total _]]]
    (return ?total)

    [["RealTotal" [?total _]]]
    (return ?total)

    [["CharTotal" [?total _]]]
    (return ?total)

    [["TextTotal" [?total _]]]
    (return ?total)

    [["TupleTotal" [?total ?structs]]]
    (if ?total
      (return true)
      (matchv ::M/objects [value-type]
        [["lux;TupleT" ?members]]
        (|do [totals (&/map% (fn [sv]
                               (|let [[sub-struct ?member] sv]
                                 (check-totality ?member sub-struct)))
                             (&/zip2 ?structs ?members))]
          (return (&/fold #(and %1 %2) true totals)))

        [_]
        (fail "")))

    [["RecordTotal" [?total ?structs]]]
    (if ?total
      (return true)
      (|do [value-type* (resolve-type value-type)]
        (matchv ::M/objects [value-type*]
          [["lux;RecordT" ?fields]]
          (|do [totals (&/map% (fn [field]
                                 (|let [[?tk ?tv] field]
                                   (if-let [sub-struct (&/|get ?tk ?structs)]
                                     (check-totality ?tv sub-struct)
                                     (return false))))
                               ?fields)]
            (return (&/fold #(and %1 %2) true totals)))

          [_]
          (fail ""))))

    [["VariantTotal" [?total ?structs]]]
    (if ?total
      (return true)
      (|do [value-type* (resolve-type value-type)]
        (matchv ::M/objects [value-type*]
          [["lux;VariantT" ?cases]]
          (|do [totals (&/map% (fn [case]
                                 (|let [[?tk ?tv] case]
                                   (if-let [sub-struct (&/|get ?tk ?structs)]
                                     (check-totality ?tv sub-struct)
                                     (return false))))
                               ?cases)]
            (return (&/fold #(and %1 %2) true totals)))

          [_]
          (fail ""))))
    
    [["DefaultTotal" ?total]]
    (return ?total)
    ))

;; [Exports]
(defn analyse-branches [analyse exo-type value-type branches]
  (|do [patterns (&/fold% (fn [patterns branch]
                            (|let [[pattern body] branch]
                              (analyse-branch analyse exo-type value-type pattern body patterns)))
                          (&/|list)
                          branches)
        ;; :let [_ (prn 'PRE_MERGE_TOTALS)]
        struct (&/fold% merge-total (&/V "DefaultTotal" false) patterns)
        ? (check-totality value-type struct)]
    (if ?
      ;; (return (&/|reverse patterns))
      (return patterns)
      (fail "[Pattern-maching error] Pattern-matching is non-total."))))
