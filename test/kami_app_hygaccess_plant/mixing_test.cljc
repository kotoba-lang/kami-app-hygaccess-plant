(ns kami-app-hygaccess-plant.mixing-test
  "Golden/regression test for the mixing-tank CFD scenario: determinism +
  physical sanity (homogeneity CoV strictly decreases as agitation proceeds,
  final CoV is low, no NaN/divergence, NaOCl mass is conserved by the closed
  cavity). Empirically run once during authoring (see docs/adr/0001 for the
  actual numbers observed: flow converges in 318 PISO steps at max Courant
  ~0.35, CoV falls from ~332% to ~1.8e-5% over 40 scalar-transport ticks,
  mass-conservation defect ~1e-12)."
  (:require [clojure.test :refer [deftest is testing]]
            [kami-app-hygaccess-plant.process :as process]
            [kami-app-hygaccess-plant.mixing :as mixing]))

(defn- run []
  (mixing/run-mixing-scenario
   {:tank (process/default-tank) :process process/default-process}))

(deftest deterministic-across-repeated-runs
  (let [r1 (run) r2 (run)]
    (is (= r1 r2) "pure functional CFD solve: identical input -> byte-identical output")
    (is (= (:mixing-homogeneity-cov-pct r1) (:mixing-homogeneity-cov-pct r2)))
    (is (= (:concentration-field-pct r1) (:concentration-field-pct r2)))))

(deftest flow-converges-without-blowing-up
  (let [{:keys [flow]} (run)]
    (is (:converged flow) "PISO reaches steady state within the step budget")
    (is (pos? (:steps-run flow)))
    (is (< (:max-courant flow) 1.0) "implicit PISO stays well inside a safe Courant range")))

(deftest homogeneity-cov-decreases-monotonically-as-mixing-progresses
  (let [{:keys [mixing-homogeneity-cov-pct-history]} (run)]
    (is (>= (count mixing-homogeneity-cov-pct-history) 2))
    (is (every? (fn [[a b]] (<= b a))
                (partition 2 1 mixing-homogeneity-cov-pct-history))
        "CoV never increases tick-over-tick — mixing only ever homogenizes here")
    (is (< (last mixing-homogeneity-cov-pct-history)
           (* 0.01 (first mixing-homogeneity-cov-pct-history)))
        "final CoV is at least 100x lower than the just-charged initial CoV")))

(deftest final-state-is-physically-sane
  (let [{:keys [mixing-homogeneity-cov-pct concentration-field-pct concentration-mean-pct
                concentration-mass-conservation-defect]}
        (run)]
    (testing "no NaN/divergence anywhere in the final concentration field"
      (is (every? (fn [v] (and (not #?(:clj (Double/isNaN v) :cljs (js/isNaN v)))
                               (not #?(:clj (Double/isInfinite v) :cljs (= v js/Infinity)))))
                  concentration-field-pct)))
    (testing "final homogeneity is low (well-mixed), not a stalled/divergent run"
      (is (< mixing-homogeneity-cov-pct 1.0) "CoV < 1% = effectively fully mixed"))
    (testing "the volume-average concentration lands at the process's target"
      (is (< (Math/abs (- concentration-mean-pct 1.0)) 1.0e-6)))
    (testing "the closed cavity conserves NaOCl mass to near machine precision"
      (is (< concentration-mass-conservation-defect 1.0e-8)))
    (testing "the process's own target is within its own registered efficacy window"
      (is (process/within-efficacy-window? process/default-process concentration-mean-pct)))))

(deftest smaller-mesh-still-converges-and-homogenizes
  (testing "the scenario isn't fragile to the exact default resolution"
    (let [tank (assoc (process/default-tank) :tank/mesh-resolution [12 12])
          {:keys [flow mixing-homogeneity-cov-pct]}
          (mixing/run-mixing-scenario {:tank tank :process process/default-process :scalar-steps 20})]
      (is (:converged flow))
      (is (< mixing-homogeneity-cov-pct 5.0)))))
