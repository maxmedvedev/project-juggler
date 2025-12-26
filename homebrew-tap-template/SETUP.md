# Setting Up Your Homebrew Tap

This directory contains template files for creating your Homebrew tap repository.

## Steps

### 1. Create a new GitHub repository

Create a new repository named `homebrew-tap` (or `homebrew-<name>`).

**Important:** Homebrew expects tap repositories to be named `homebrew-*`.

### 2. Initialize the repository

```bash
cd /path/to/new/repo
git init
```

### 3. Copy template files

Copy the contents of this `homebrew-tap-template` directory to your new repository:

```bash
cp -r homebrew-tap-template/* /path/to/new/repo/
```

### 4. Update placeholders

In `Formula/idea-juggler.rb` and `README.md`, replace:
- `YOUR_USERNAME` with your GitHub username
- `PUT_CHECKSUM_HERE` with the actual SHA256 checksum from your first release
- Update the license if needed

### 5. Commit and push

```bash
cd /path/to/new/repo
git add .
git commit -m "Initial commit: Homebrew formula for idea-juggler"
git branch -M main
git remote add origin https://github.com/YOUR_USERNAME/homebrew-tap.git
git push -u origin main
```

### 6. Test installation

```bash
brew tap YOUR_USERNAME/tap
brew install idea-juggler
idea-juggler --help
```

## Automating Formula Updates (Optional)

You can automate formula updates by adding a workflow to your main idea-juggler repository that pushes formula updates to the tap repository. This requires:

1. A Personal Access Token with `repo` and `workflow` permissions
2. Add it as `HOMEBREW_TAP_TOKEN` secret in your idea-juggler repository settings
3. Update the `.github/workflows/release.yml` to include the formula update job

See the plan documentation for the complete workflow.
