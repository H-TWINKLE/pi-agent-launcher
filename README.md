# Pi Agent Launcher

[中文文档](README_CN.md)

One-click [Pi coding agent](https://pi.dev) launcher for JetBrains IDEs — opens a "Pi" tab inside the Terminal tool window and starts `pi` automatically.

[![JetBrains Plugin](https://img.shields.io/badge/JetBrains-Plugin-orange)](https://plugins.jetbrains.com/plugin/31737-pi-agent-launcher)
[![License: MIT](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)

## Features

- **One-click launch** — Click the π button in the toolbar to start Pi
- **Terminal integration** — Pi runs as a tab inside the IDE's Terminal window (alongside Local)
- **Send to Pi** — Select code → Right-click → "Send to Pi" inserts `@path/file.go#L10-25` into the active terminal tab
- **Copy reference** — Right-click code or a file → "Copy Code/File Reference" puts `@path/file.go#L10-25` on the clipboard
- **Terminal support** — Sends references to the active terminal tab, including the new terminal engine used by recent WebStorm/IntelliJ versions
- **Auto-open files** — Files Pi reports as modified open in the editor, and a notification summarises each run
- **Process notifications** — Get notified when Pi exits unexpectedly or fails to start
- **Model configuration** — Pick model and thinking level from `~/.pi/agent/models.json`

## Keyboard Shortcuts

| Shortcut | Action |
|----------|--------|
| `Cmd+Esc` / `Ctrl+Esc` | Launch or focus Pi |
| `Cmd+Shift+P` / `Ctrl+Shift+P` | Send selection to Pi |

## Quick Start

1. Install the plugin from [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/31737-pi-agent-launcher)
2. Ensure `pi` CLI is installed and in your PATH
3. Click the **π** button in the toolbar
4. A "Pi" tab opens in the Terminal window and `pi` starts automatically

## Configuration

**Settings → Tools → Pi Agent**

- **Model** — Select from models defined in `~/.pi/agent/models.json`
- **Custom model id** — Override the dropdown with any model identifier
- **Thinking level** — Default / none / low / medium / high / max
- **Pi command** — Custom path to pi binary
- **Extra arguments** — Additional CLI flags
- **Disable Pi themes** — Append `--no-themes` so Pi follows the IDE theme instead of its own
- **Auto-open files** — Toggle auto-opening the files Pi modified
- **Notifications** — Toggle file-change, startup/exit and clipboard-copy notifications separately

## Supported IDEs

Works with all JetBrains IDEs: IntelliJ IDEA, GoLand, PyCharm, WebStorm, PhpStorm, CLion, Rider, RubyMine, and more.

## Development

```bash
# Build
./gradlew build

# Run sandbox IDE for testing
./gradlew runIde

# Package
./gradlew buildPlugin
```

## Project Structure

```
src/main/kotlin/com/piagent/launcher/
├── actions/
│   ├── LaunchPiAction.kt          # Toolbar button → launch Pi
│   ├── OpenPiAction.kt            # Cmd+Esc → focus Pi
│   ├── SendSelectionAction.kt     # Send @file#L reference to the active terminal
│   ├── SendFileToPiAction.kt      # Send @file reference from Project View
│   ├── CopyCodeReferenceAction.kt # Copy @file#L reference to the clipboard
│   ├── CopyFileReferenceAction.kt # Copy @file reference to the clipboard
│   ├── PiActionData.kt            # Shared file/editor data lookup
│   ├── PiClipboard.kt             # Clipboard copy + send-failure fallback
│   └── PiNotifications.kt         # Action feedback notifications
├── services/
│   ├── PiTerminalService.kt       # Terminal lifecycle + send text
│   ├── PiBridgeServer.kt          # Loopback endpoint for the pi extension
│   ├── PiBridgeInstaller.kt       # Installs pi-ide-bridge.ts (version-checked)
│   ├── PiFileTracker.kt           # Opens files reported by the bridge
│   └── PiStatusWidget.kt          # Status bar widget
├── settings/
│   ├── PiSettings.kt              # Persistent config
│   ├── PiSettingsConfigurable.kt  # Settings UI panel
│   └── PiModelLoader.kt           # Load models from models.json
└── references/
    └── PiReference.kt             # Build @path and @path#L10-25 references
```

## License

MIT
