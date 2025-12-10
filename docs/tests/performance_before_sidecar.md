# Performance Baseline (Before Sidecar Implementation)

**Generated:** 2025-12-09

**System:** Browser-based DataScript (Node.js v22.21.0, no JVM sidecar)

**Test Environment:** Windows 11 ARM64

---

## Summary

This document captures baseline performance metrics before implementing the JVM sidecar.
These numbers serve as the "before" comparison for measuring sidecar improvements.

### Target Improvements (from sidecar plan)

| Metric | Current Baseline | Target | Expected Improvement |
|--------|-----------------|--------|----------------------|
| Startup (2K pages) | 30-90 sec | < 5 sec | 6-18x faster |
| Query latency (simple) | 50-200 ms | < 20 ms | 2.5-10x faster |
| Query latency (complex) | 200-800 ms | < 100 ms | 2-8x faster |
| Memory (2K pages) | 400-800 MB | < 300 MB | 25-60% less |
| IPC round-trip | N/A | < 5 ms | new metric |

---

## Benchmark Results

### Database Load Performance

| Graph Size | Pages | Blocks | Load Time (ms) | Notes |
|------------|-------|--------|----------------|-------|
| Small | 100 | 1,000 | **1,123.80** | Baseline for quick operations |
| Medium | 500 | 7,500 | **9,106.49** | ~8x slower than small (linear scaling) |
| Large | 2,000 | 20,000 | _not tested_ | Would likely be 30-60+ seconds |

**Observation:** Load time scales roughly linearly with data size. The medium graph (7.5x more blocks) takes ~8x longer than the small graph.

### Query Performance (averaged over 50-100 iterations)

| Query Type | Avg Time (ms) | Description |
|------------|---------------|-------------|
| Simple entity lookup | **0.36** | `[:find ?e :where [?e :block/name "..."]]` |
| Backlinks pattern | **2.51** | Full-text search for page references |
| Property filter | **6.98** | Filter blocks by content pattern (TODO search) |
| Pull entity | **0.03** | `(d/pull db '[*] eid)` |

**Observations:**
- Simple queries are very fast (sub-millisecond) on small graphs
- Pattern matching queries (backlinks, property filter) are 7-20x slower
- Pull operations are extremely fast for single entities

### Transaction Performance

| Operation | Avg Time (ms) | Description |
|-----------|---------------|-------------|
| Single block insert | **1.15** | Insert one block with timestamp |

**Observation:** Single block transactions are fast. Bulk operations would benefit from batching.

### Storage Metrics

| Metric | Value | Description |
|--------|-------|-------------|
| Datom count (small) | **7,596** | Total datoms for 100-page graph (1,000 blocks) |
| Datoms per block | ~7.6 | Average datoms per block entity |

**Observation:** Each block generates approximately 7-8 datoms on average (uuid, title, page, parent, order, timestamps, etc.).

---

## Key Findings

### 1. Current Performance is Good for Small Graphs

The measured query latencies (0.03-7ms) are actually much better than the 50-200ms baseline cited in the sidecar plan. This suggests:

- **The plan's baseline may have been for larger graphs** (10K+ blocks)
- **Or measured under different conditions** (browser vs Node.js, debug vs release)
- **Memory pressure** on larger graphs likely degrades query performance significantly

### 2. Load Time is the Primary Bottleneck

For a medium graph (7,500 blocks), loading takes **9+ seconds**. Extrapolating:
- 20,000 blocks → ~25-30 seconds
- 50,000 blocks → ~60+ seconds

This aligns with the sidecar plan's target of reducing startup from 30-90 seconds to <5 seconds.

### 3. Pattern Matching Queries Scale Poorly

The property filter query (6.98ms) is ~20x slower than simple lookups (0.36ms). On larger graphs, these queries could easily reach 100-800ms as noted in the sidecar plan.

### 4. Sidecar Opportunities

Based on these results, the sidecar should prioritize:

1. **Lazy loading** - Don't load all blocks at startup
2. **Indexed queries** - Pre-compute backlinks and common patterns
3. **Memory management** - Use soft references to evict unused data
4. **Background parsing** - Parse markdown files on demand

---

## How to Run Benchmarks

### Prerequisites

```bash
# Ensure dependencies are installed
yarn install

# Compile the test code
yarn cljs:test
```

### Running Baseline Benchmarks

```bash
# Option 1: Using bb task
bb benchmark:baseline

# Option 2: Direct test execution with benchmark filter
yarn cljs:run-test -i benchmark

# Option 3: Run specific benchmark
yarn cljs:run-test -i benchmark -n frontend.sidecar.baseline-benchmark-test/query-simple-benchmark
```

### Interpreting Results

The benchmarks output results to the console in this format:

```
📊 Small graph load: 1123.80ms
📊 Simple query avg: 0.36ms (over 100 iterations)
📊 Backlinks query avg: 2.51ms
📊 Property filter query avg: 6.98ms
📊 Pull entity avg: 0.03ms
📊 Single block tx avg: 1.15ms
📊 Datom count (small graph): 7596
```

---

## Test Configuration

| Parameter | Value |
|-----------|-------|
| Small Graph | 100 pages × 10 blocks = 1,000 blocks |
| Medium Graph | 500 pages × 15 blocks = 7,500 blocks |
| Large Graph | 2,000 pages × 10 blocks = 20,000 blocks |
| Query Iterations | 50-100 per query type |
| Node.js Version | v22.21.0 |
| Platform | Windows 11 ARM64 |

---

## Benchmark Implementation Details

The benchmarks are implemented in:
- `src/test/frontend/sidecar/baseline_benchmark_test.cljs`

Key utilities used:
- `frontend.util/with-time` - Measures execution time
- `datascript.core/q` - DataScript queries
- `datascript.core/transact!` - Database transactions
- `datascript.core/datoms` - Datom enumeration

### Graph Generation

Test graphs are generated programmatically with:
- Unique UUIDs for each page and block
- Realistic block content with links and tags (`[[PageLink#]]`, `#tag#`, `TODO:`)
- Proper `block/order` for outliner structure

---

## Notes

- All timings measured using `frontend.util/with-time` macro
- Query benchmarks averaged over multiple iterations for stability
- Memory measurements only available in environments with `performance.memory` API
- Results may vary based on system load and available memory
- These tests run in Node.js; browser performance may differ

---

## After Sidecar Implementation

Once the JVM sidecar is implemented, run:

```bash
bb benchmark:compare
```

This will compare the baseline results with sidecar-enabled performance.

Expected improvements based on sidecar plan targets:

| Metric | Before (measured) | Target | Expected Change |
|--------|-------------------|--------|-----------------|
| Small graph load | 1,123 ms | <200 ms | 5x faster |
| Medium graph load | 9,106 ms | <1,500 ms | 6x faster |
| Simple query | 0.36 ms | <0.5 ms | maintained |
| Complex query | 6.98 ms | <5 ms | 30% faster |
| IPC overhead | N/A | <5 ms | new metric |

---

## Appendix: Raw Results

```edn
;; Benchmark results from 2025-12-09
{:timestamp "2025-12-09T20:35:00Z"
 :environment {:node-version "v22.21.0"
               :platform "win32"
               :arch "arm64"}
 :results
 [{:category "db-load"
   :metric "load-100-pages-1000-blocks"
   :value 1123.80
   :unit "ms"}
  {:category "db-load"
   :metric "load-500-pages-7500-blocks"
   :value 9106.49
   :unit "ms"}
  {:category "query"
   :metric "simple-entity-lookup-avg"
   :value 0.36
   :unit "ms"}
  {:category "query"
   :metric "backlinks-pattern-avg"
   :value 2.51
   :unit "ms"}
  {:category "query"
   :metric "property-filter-avg"
   :value 6.98
   :unit "ms"}
  {:category "query"
   :metric "pull-entity-avg"
   :value 0.03
   :unit "ms"}
  {:category "transaction"
   :metric "single-block-avg"
   :value 1.15
   :unit "ms"}
  {:category "storage"
   :metric "datom-count-100-pages"
   :value 7596
   :unit "datoms"}]}
```
