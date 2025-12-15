# Logsidian

**Obsidian's speed with Logseq's blocks, files stay yours.**

Logsidian is a high-performance outliner and knowledge base that keeps your markdown files as the source of truth.

## Features

- Block-level editing with powerful outliner operations
- Bidirectional linking and graph visualization
- Local-first: your files, your data
- Markdown and Org-mode support
- Plugin ecosystem

## Status

**Early Development** - Not ready for production use.

## Disclaimer

Logsidian is an independent open-source project. It is not affiliated with, endorsed by, or connected to Logseq Inc. or Dynalist Inc. (makers of Obsidian).

- Logseq is a trademark of Logseq Inc.
- Obsidian is a trademark of Dynalist Inc.

## Development

```bash
# Install dependencies
yarn install

# Start development server
yarn watch

# Build for production
yarn release
```

## Debugging

### Sidecar Debug Logging

Enable verbose logging for the JVM sidecar to diagnose sync and query issues:

```bash
# Via environment variable
LOGSIDIAN_DEBUG=true java -jar sidecar/target/logsidian-sidecar.jar

# Via JVM system property
java -Dlogsidian.debug=true -jar sidecar/target/logsidian-sidecar.jar
```

Debug output goes to console and `sidecar-debug.log` file.

### Frontend Routing Debug

Enable routing decision logging in the browser console:

```javascript
// Enable debug logging
frontend.sidecar.routing.enable_debug_BANG_()

// Disable debug logging
frontend.sidecar.routing.disable_debug_BANG_()
```

Shows `[ROUTING->SIDECAR]` or `[ROUTING->WORKER]` for each operation.

## License

AGPL-3.0 - Based on [Logseq](https://github.com/logseq/logseq)

## Acknowledgments

Logsidian is built on the excellent foundation of [Logseq](https://logseq.com/), an open-source knowledge management tool. We are grateful to the Logseq team and community for their work.
