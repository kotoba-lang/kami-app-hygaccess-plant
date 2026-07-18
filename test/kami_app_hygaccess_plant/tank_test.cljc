(ns kami-app-hygaccess-plant.tank-test
  (:require [clojure.test :refer [deftest is]]
            [kami-app-hygaccess-plant.tank :as tank]))

(def sample
  #:tank{:cross-section-m [0.6 0.6] :depth-m 0.6 :mesh-resolution [24 24]
         :agitation #:agitation{:drive-velocity-m-s 0.5}})

(deftest cross-section-area-is-width-times-height
  (is (= 0.36 (tank/cross-section-area-m2 sample))))

(deftest batch-volume-is-area-times-depth-in-liters
  (is (= 216.0 (tank/batch-volume-l sample))))

(deftest mesh-params-map-cross-section-and-resolution-to-block-mesh-args
  (is (= {:nx 24 :ny 24 :lx 0.6 :ly 0.6} (tank/mesh-params sample))))

(deftest agitation-drive-velocity-reads-tank-data-with-a-fallback
  (is (= 0.5 (tank/agitation-drive-velocity-m-s sample)))
  (is (= 0.5 (tank/agitation-drive-velocity-m-s (dissoc sample :tank/agitation)))))
