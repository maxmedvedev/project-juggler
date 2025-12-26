# Homebrew Tap for idea-juggler

## Installation

```bash
brew tap YOUR_USERNAME/tap
brew install idea-juggler
```

## Verification

```bash
idea-juggler --help
```

## Development

To test the formula locally:

```bash
brew install --build-from-source Formula/idea-juggler.rb
```

## Updating the Formula

When a new version of idea-juggler is released:

1. Update the `url` in `Formula/idea-juggler.rb` to point to the new release
2. Update the `sha256` with the checksum from the release artifacts
3. Commit and push the changes

Example:

```ruby
url "https://github.com/YOUR_USERNAME/idea-juggler/releases/download/v1.0.1/idea-juggler-1.0.1.tar.gz"
sha256 "abc123..."
```
