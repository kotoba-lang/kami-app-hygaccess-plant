# kami-app-hygaccess-plant

[![CI](https://github.com/kotoba-lang/kami-app-hygaccess-plant/actions/workflows/ci.yml/badge.svg)](https://github.com/kotoba-lang/kami-app-hygaccess-plant/actions/workflows/ci.yml)

A product-visualization piece for `cloud-itonami/cloud-itonami-hygiene-access`
(an open-business-blueprint actor commercializing affordable hygiene/
disinfectant actives — sodium hypochlorite, IPMP — for water-scarce /
poor-sanitation-infrastructure markets): **a real finite-volume CFD
simulation of the sodium-hypochlorite dilution/mixing step** for
`int.hygaccess.water-purification-drops`, rather than a hand-waved number.

Built entirely on the workspace's canonical `kami-engine` 3D stack
(ADR-2607102200): domain logic in portable `.cljc`, the CFD kernel is
[`kotoba-lang/nagare`](https://github.com/kotoba-lang/nagare), the solve is
dispatched through
[`kotoba-lang/kami-engine-cae-solver`](https://github.com/kotoba-lang/kami-engine-cae-solver)'s
shared `cae.solver/solve` contract, and the visualization is an EDN render-IR
frame in `kotoba-lang/webgpu`'s `kami.webgpu.ir` shape. No Three.js, no
Babylon.js, no CSS-3D fakery, no bespoke per-app renderer, no new Rust crate.

## Scope

**ONE representative illustrative process, not a general/validated/certified
CFD tool.** Following the convention established by `cloud-itonami-isic-2029`'s
ADR (which scoped a residual "n.e.c." category to one concrete product line
rather than the whole bucket), this repo simulates exactly one thing: a
concentrated NaOCl stock solution being diluted/mixed with water in a small-
batch mixing tank, driven by an agitator/inlet-jet, for the
`int.hygaccess.water-purification-drops` SKU. **Other SKUs' mixing processes
(surface-disinfectant, antibacterial-soap) are structurally analogous — the
same class of problem, liquid-liquid dilution mixing in a stirred/jet-driven
tank — but are not separately simulated in this build.**

Tank dimensions, agitation drive speed, and mixing duration are
**representative / illustrative**, not a certified engineering drawing or a
real production tank's procurement spec (see `tank.cljc` / `process.cljc`
docstrings for exactly what is representative vs. what is cited from a real
source). The CFD mesh is a coarse 2-D vertical cross-section (24x24 cells by
default) with a unit-depth assumption, not a resolved 3-D vessel.

## Where the numbers come from

| Figure | Value | Source |
|---|---|---|
| `:process/target-concentration-pct` | 1.0% w/v | `cloud-itonami/cloud-itonami-hygiene-access` `products.edn` `:hygaccess.product/concentration-pct 1.0` for `int.hygaccess.water-purification-drops` |
| `:process/efficacy-window-pct` | `[0.5 1.5]`% w/v | same repo, `src/hygaccess/registry.cljc` `efficacy-window-pct` `[:sodium-hypochlorite :water-purification-drops]` |
| `:process/stock-feed-concentration-pct` | 12% w/v | **this build's own representative choice** — commercial technical-grade NaOCl solution is commonly supplied around 10-15% w/v; not sourced from a specific supplier spec |
| Tank cross-section, agitation drive speed, mixing duration, turbulent diffusivity | see `process.cljc` | representative small-batch figures, documented as such at each definition |

## The headline number: `:mixing-homogeneity-cov-pct`

The simulation reduces to one clearly-named number: **the coefficient of
variation (std-dev / mean, as a percentage) of the NaOCl concentration field
across every finite-volume cell, after the simulated agitation window** — 0%
would be perfectly uniform (fully mixed), larger is less homogeneous. It is
written into the result map as `:mixing-homogeneity-cov-pct`, alongside a
per-tick `:mixing-homogeneity-cov-pct-history` showing it fall as agitation
proceeds.

This is named to match the field-name convention a QC/batch-record data model
(e.g. the sibling `cloud-itonami-hygiene-access` GMP/QA-data-model work, built
separately and not depended on by this repo) could cite it under — a
**loose EDN-level naming convention only, no code dependency** between the two
repos. This repo does not itself produce a Certificate of Analysis or any
GMP-format record; it produces one clearly-documented simulation result a
human (or that other repo, later) can read and understand.

### What the golden test (`mixing_test.cljc`) actually observed

Running the default scenario (`process/default-tank` + `process/default-process`,
24x24 mesh, 0.5 m/s agitation drive):

- **Flow**: PISO converges in **318 steps**, max cell Courant number **~0.35**
  (well inside a safe range).
- **Scalar transport**: 40 ticks over the 90s simulated mixing window. Homogeneity
  CoV falls from **~332%** (just after the concentrated charge is poured in, a
  small high-concentration blob against a mostly-zero background) to
  **~1.8e-5%** (essentially perfectly mixed) — **strictly decreasing every
  single tick**, no oscillation, no NaN, no divergence.
- **Mass conservation**: the closed-cavity topology (see `mixing.cljc` for why)
  conserves NaOCl mass to ~1e-12 (floating-point noise, not a modeling
  approximation) — the volume-average concentration lands at **1.0000000000019%**,
  matching the target exactly by construction of the charge sizing.
- **Determinism**: running the scenario twice from the same inputs produces
  **byte-identical output** (`= r1 r2` — a pure functional finite-volume solve,
  no randomness, no floating-point-order nondeterminism across runs on the
  same platform).

## Architecture

See `docs/adr/0001-architecture.md` for the full account (including the
mesh-topology and license decisions). Namespace map:

| Namespace | Contents |
|---|---|
| `kami-app-hygaccess-plant.tank` | Vessel/tank domain model (`:tank/...` keys, mirrors `kami-app-giemon-factory`'s `:factory/...` convention) |
| `kami-app-hygaccess-plant.process` | Batch/process parameters (`:process/...`), `default-tank` / `default-process` |
| `kami-app-hygaccess-plant.mixing` | The actual CFD: mesh + BCs -> nagare PISO flow solve -> transient scalar-transport loop -> homogeneity CoV |
| `kami-app-hygaccess-plant.solve` | Registers `cae.solver/solve :hygaccess-mixing-tank` |
| `kami-app-hygaccess-plant.render` | render-IR construction (mirrors `kami.webgpu.ir`'s documented contract — see its docstring / Render status below for why it doesn't depend on the Var directly) |

```clojure
(require '[cae.solver :as solver] '[kami-app-hygaccess-plant.solve])

(solver/solve {:solver {:kind :hygaccess-mixing-tank}})
;; => {:solver :hygaccess-mixing-tank
;;     :mesh {:nx 24 :ny 24 :lx 0.6 :ly 0.6 :dx 0.025 :dy 0.025 :n-cells 576}
;;     :flow {:nu-eff-m2-s 0.005 :dt-s 0.02 :steps-run 318 :converged true :max-courant 0.346}
;;     :scalar {:diffusivity-m2-s 0.003 :dt-s 2.25 :steps 40 :dilution-ratio 12.0}
;;     :concentration-field-pct [...576 doubles...]
;;     :concentration-mean-pct 1.0000000000018956
;;     :mixing-homogeneity-cov-pct-history [331.66 ... 1.83e-5]
;;     :mixing-homogeneity-cov-pct 1.8298324379849743E-5
;;     :fidelity :finite-volume-reference :status :screening-only ...}
```

## Render status (honest accounting — read before assuming this "renders")

- **Proven / tested**: the render-IR construction itself
  (`kami-app-hygaccess-plant.render`) — `render-ir`, `instance`, `sky`,
  `valid?`, `concentration->color`, and `tank-render-ir` (one instance per
  finite-volume cell, colour-mapped by normalized concentration). These are
  pure `.cljc` functions with JVM tests (`render_test.cljc`) proving the
  output is well-formed (`valid?`) and deterministic — no browser, no WebGPU
  context needed for that proof.
- **Wired but unverified**: actually calling `kami.webgpu/init!` + `draw!`
  (or the WebGL 2.0 fallback it selects automatically) on the render-IR this
  repo produces, to get pixels on screen. This repo's render-IR is
  **structurally identical** to `kami.webgpu.ir`'s documented shape
  (`{:globals {:sky {...} :eye [...] :target [...]} :instances [{:pos :color
  :size :yaw} ...]}`), so wiring it through is expected to be a direct
  `(gpu/draw! ctx (render/tank-render-ir result))` call from inside a browser
  build — but that call has not actually been exercised on screen.
- **Known, confirmed gap — not declared as a hard dependency, and why**:
  `kotoba-lang/webgpu` is NOT in this repo's `deps.edn`. Empirically confirmed
  (`clojure -Stree` against a standalone `deps.edn` referencing
  `io.github.kotoba-lang/webgpu` by git SHA):

  ```
  Error building classpath. Local lib io.github.kotoba-lang/expr not found: ~/.gitlibs/libs/io.github.kotoba-lang/webgpu/expr
  ```

  `kotoba-lang/webgpu`'s own `deps.edn` declares ~20 sibling packages
  (`org-w3-webgpu`, `wgsl`, `expr`, `gpu`, `webgl`, `sky`, `render`,
  `render-shaders`, `scene2d`, `dance`, `physics`, `fsm`, `netsync`,
  `pipelines`, `level`, `host`, `playwright`, `cartpole-math`, `sprite-gpu`,
  `sprite2d`) purely via `:local/root "../X"` — it only resolves when checked
  out as a sibling inside the west-managed `kotoba-lang` workspace
  (`orgs/kotoba-lang/*`), which is also true of every actual browser-rendering
  `kami.webgpu` consumer surveyed while building this repo (no existing
  `kami-app-*` repo in this org declares `io.github.kotoba-lang/webgpu` as a
  standalone git dep either — `kami-app-car-sim` / `kami-app-giemon-factory` /
  `kami-app-sarutahiko-factory` are all explicitly domain-data-only, wgpu
  rendering not ported). Declaring it here would break `clojure -M:test` for
  anyone cloning this repo outside that workspace, which is a worse outcome
  than being honest that the render step is unverified. Inside the
  `kotoba-lang` west workspace, the actual wiring is:

  ```clojure
  (require '[kami.webgpu :as gpu] '[kami-app-hygaccess-plant.render :as render])
  (-> (gpu/init! canvas)
      (.then (fn [ctx] (gpu/draw! ctx (render/tank-render-ir result)))))
  ```

  See `docs/adr/0001-architecture.md` Decision 4 for the full account.
- **Do not** read the presence of `render.cljc` as proof of an on-screen
  visualization — it is proof of a well-formed, deterministic render-IR
  frame, nothing more.

## Develop

```bash
clojure -M:test     # 22 tests / 63 assertions (mixing solve determinism + physical
                     # sanity, process/tank domain model, cae.solver dispatch, render-IR)
clojure -M:lint      # clj-kondo, 0 errors
```
