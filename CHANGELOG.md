<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Inline FIM Changelog

## [Unreleased]

### Added

- Inline code completion from a local fill-in-the-middle model via Ollama,
  shown as grey text and accepted with Tab.
- Asymmetric context window around the caret (3000 chars before, 1000 after),
  snapped to line boundaries.
- Streaming with debounce, cancellation on the next keystroke, and an LRU cache
  of recent contexts.
- Silence rules: mid-word, inside strings and comments, line already complete,
  trailing text on the line, and suggestions that only echo the suffix. Each is
  individually configurable.
- Accept/dismiss telemetry written as JSONL, recording shape and outcome only --
  never source text or prompts.
- Model warm-up at project open, so the first keystroke does not pay the cold
  load.
- Settings panel under Tools for model, debounce, token and line caps, and the
  silence rules.
