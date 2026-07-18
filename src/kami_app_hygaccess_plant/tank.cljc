(ns kami-app-hygaccess-plant.tank
  "The mixing-tank vessel model — ONE illustrative small-batch mixing tank for
  `int.hygaccess.water-purification-drops` (see README.md 'Scope'). Namespace-per-
  domain-type EDN layout, mirroring `kami-app-giemon-factory`'s convention
  (`:factory/...` keys there, `:tank/...` here): callers supply a plain map of
  namespaced keys; this namespace only holds pure derivation functions, no I/O.

  `:tank/...` fields describe a REPRESENTATIVE / illustrative small-batch mixing
  tank, not a certified engineering drawing:

  - `:tank/cross-section-m` — `[width height]` of the 2-D vertical cross-section
    the CFD solve runs on (nagare's `mesh/block-mesh` is a 2-D unit-depth block —
    see docs/adr/0001-architecture.md Decision 1 for why a full 3-D vessel is out
    of scope for this build).
  - `:tank/depth-m` — the unit-depth assumption's real-world depth, used only to
    convert the 2-D cross-section area into a batch volume estimate; the CFD solve
    itself is depth-invariant (that is what 'unit depth' means in nagare's mesh).
  - `:tank/mesh-resolution` — `[nx ny]` cell counts for `mesh/block-mesh`.
  - `:tank/agitation` — how bulk recirculation is driven during the mixing phase.
    Modeled as a shear-driven boundary condition on the tank's top face (the SAME
    topology nagare's own Ghia-validated lid-driven-cavity demo uses — see
    docs/adr/0001-architecture.md Decision 2 for why: an open jet inlet + outlet
    pair would need a hand-derived mass-conserving BC combination nagare's own
    test suite doesn't cover, which is an unnecessary numerical-safety risk for
    an illustrative build).
  - `:tank/inlet` / `:tank/outlet` — DOMAIN-DATA ONLY (nozzle / valve description).
    The inlet's stock-feed concentration DOES drive the scalar (concentration)
    boundary condition (see `mixing.cljc`); the outlet is NOT a simultaneously-open
    CFD boundary during the simulated agitation window — it describes the
    separate post-mixing drain-to-filling-line step, which this build does not
    itself simulate."
  )

(defn cross-section-area-m2
  "Plan area of the 2-D cross-section, `width * height` (m^2)."
  [{:tank/keys [cross-section-m]}]
  (let [[w h] cross-section-m]
    (* (double w) (double h))))

(defn batch-volume-l
  "Illustrative batch volume: cross-section area * depth, in liters.
  `1 m^3 = 1000 L`. This is a REPRESENTATIVE figure for a small-batch mixing
  tank, not a procurement spec."
  [{:tank/keys [depth-m] :as tank}]
  (* (cross-section-area-m2 tank) (double depth-m) 1000.0))

(defn mesh-params
  "nagare `mesh/block-mesh` args derived from this tank's cross-section +
  resolution: `{:nx :ny :lx :ly}`."
  [{:tank/keys [cross-section-m mesh-resolution]}]
  (let [[w h] cross-section-m
        [nx ny] mesh-resolution]
    {:nx nx :ny ny :lx (double w) :ly (double h)}))

(defn agitation-drive-velocity-m-s
  "The representative tangential drive velocity imparted at the tank's top
  face by the agitator / inlet-jet momentum (the nagare lid-driven-cavity-style
  BC's `U = (v, 0)`). Falls back to 0.5 m/s (a representative small paddle-tip /
  jet speed for a small-batch tank) if the tank map doesn't specify one."
  [{:tank/keys [agitation]}]
  (double (or (:agitation/drive-velocity-m-s agitation) 0.5)))
