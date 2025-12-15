# GraalJS Experiment Archive

This directory contains the archived GraalJS mldoc parsing experiment.

## Why Archived

The GraalJS approach was explored as a way to run mldoc (OCaml→JavaScript) parsing directly in the JVM sidecar. However, testing revealed significant performance issues:

| Content Size | GraalJS Interpreter | V8 (Worker) |
|--------------|---------------------|-------------|
| Small (~100 bytes) | 31ms | <1ms |
| Medium (~2KB) | 288ms | ~5ms |
| Large (~10KB) | 7,388ms | ~20ms |

### Key Findings

1. **GraalJS Interpreter Mode**: Without JIT compilation, GraalJS runs mldoc.js in interpreter mode which is 10-100x slower than V8's JIT-compiled execution.

2. **Windows ARM64 Limitation**: GraalVM doesn't provide native JIT support for Windows ARM64, requiring x64 emulation which further degrades performance.

3. **Alternative: AST Streaming**: Instead of parsing in sidecar, we adopted AST streaming where:
   - Worker parses files using V8 mldoc (fast, native)
   - Worker sends parsed AST to sidecar
   - Sidecar extracts pages/blocks from AST (pure Clojure)

## Files

- `src/mldoc.clj` - GraalJS polyglot integration for mldoc
- `src/utf8.clj` - UTF-8 position/offset conversion utilities
- `test/mldoc_test.clj` - Basic mldoc parsing tests
- `test/mldoc_graaljs_test.clj` - GraalJS engine tests
- `test/mldoc_benchmark.clj` - Performance benchmarks

## Potential Future Use

These files could be useful if:
- GraalVM releases native ARM64 JIT support for Windows
- A faster JavaScript engine becomes available for JVM
- Performance requirements change for batch operations

## Date Archived

December 2025
