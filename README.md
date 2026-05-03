# JSON Tools (IntelliJ Plugin)

JSON Tools is an IntelliJ tool-window plugin for **large JSON formatting and semantic diff**.
It is designed to stay responsive with big payloads by doing parsing/diff work off the UI thread.

## What It Does

- **Strict JSON formatting**
  - Pretty-prints JSON (2-space indentation).
  - Uses a two-layer strict validation pipeline:
    - `StrictJsonValidator` (scanner-level checks for trailing commas, comments, single quotes, etc.)
    - Gson `Strictness.STRICT` (RFC 8259 parsing)
  - Returns clear error messages with line/column when possible.

- **Semantic JSON compare (order-insensitive)**
  - Compares two JSON documents while ignoring:
    - object key order
    - array element order
  - Highlights differences side-by-side:
    - Added (right-only)
    - Removed (left-only)
    - Changed
  - After compare, automatically opens diff view and jumps to the first difference.
  - Navigate diffs with **Previous / Next**.

- **Tree view inside Format tab**
  - Switch text ↔ tree view.
  - Collapse/expand controls (all, one-level, recursive).

- **Built for large JSON**
  - Background worker for parse/format/diff.
  - Diff rendering strategy optimized to reduce UI churn.
  - Array matching includes exact match, identity-key match (`id`, `_id`, `uuid`), structural fingerprint fallback, and similarity fallback.

- **UI localization**
  - Language switcher with persisted manual override.
  - Bundles included: English, Chinese, Japanese, Korean, French, German, Russian, Hindi, Turkish, Arabic, Spanish, Portuguese.

## Build & Packaging

### Requirements

- Gradle wrapper included (`./gradlew`)
- Gradle runtime: **JDK 11+**
- Target bytecode: **JVM 17**

### Build Commands

```bash
# Build plugin zip for installation
./gradlew clean buildPlugin

# Compile only
./gradlew compileKotlin

# Run sandbox IDE with plugin
./gradlew runIde
```

### Output Artifacts

- `build/distributions/json-tools-<version>.zip` (installable plugin package)
- `build/libs/instrumented-json-tools-<version>.jar` (instrumented plugin jar)

Current project version is defined in `build.gradle.kts`.

## Installation

In IntelliJ IDEA:

1. Open **Settings → Plugins**
2. Click ⚙️ → **Install Plugin from Disk...**
3. Select `build/distributions/json-tools-<version>.zip`
4. Restart IDE

## Compatibility Declaration

Compatibility range is patched at build time:

- `since-build = 181`
- `until-build = 999.*`

Notes:

- This is a broad declaration range for installation compatibility.
- Runtime compatibility still depends on platform/API/JBR changes in specific IDE versions.
- Build warnings about old `since-build` vs JVM target are expected with this strategy.

## Project Structure (Main Files)

```text
src/main/kotlin/com/example/jsontools/
  JsonToolsPanel.kt         # Tool-window UI, compare rendering, navigation, i18n switch
  JsonDiffer.kt             # Semantic diff engine and array matching strategy
  JsonFormatter.kt          # Strict parse + pretty-print entry
  StrictJsonValidator.kt    # Scanner-level strict JSON checks
  JsonToolsBundle.kt        # Resource bundle + locale override logic

src/main/resources/
  META-INF/plugin.xml
  messages/JsonToolsBundle*.properties
```

## Usage Quick Start

1. Open **JSON Tools** tool window.
2. Use **Format** tab to format JSON and optionally inspect tree view.
3. Use **JSON Compare** tab:
   - paste left/right JSON
   - click **Compare**
   - use **Previous / Next** to navigate differences
   - use **Back to Edit** to return to editable mode

Diff summary/status is shown in the compare toolbar area.
