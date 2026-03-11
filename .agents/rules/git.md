# Git and Commits

## Commit Signing

**CRITICAL: All commits MUST be signed (GPG or SSH, whichever is already configured on the system).** The `main` branch is protected and will reject unsigned commits. Always use `git commit -S` or configure Git to sign commits by default:

```bash
git config --global commit.gpgsign true
```

## Merge Strategy

**All merges MUST use squash merge.** This keeps the `main` branch history clean and linear, with each feature/fix represented by a single, well-documented commit.

```bash
# Squash merge a feature branch into main
git checkout main
git merge --squash feature-branch
git commit -S -F - <<'EOF'
feat(scope): summary

- Bullet points describing changes
- ...

Co-authored-by: Name <email>
EOF
```

**Why squash merge:**
- Clean, linear history on `main`
- Each feature is a single atomic commit
- Easier to revert entire features if needed
- Commit message can summarize all changes cohesively

**After squash merge:**
- Delete the feature branch locally: `git branch -d feature-branch`
- Delete the remote branch: `git push origin --delete feature-branch`

## Commit Message Format

All commits should include a detailed description of what changed. Use a short Conventional Commit-style subject line, and a body with bullet points describing the key modifications.

- Subject: `type(scope): summary` (keep concise, <= 72 chars).
- Body: 3-6 bullets describing what was changed (and why if helpful).
- When scripting a multi-line body, use literal newlines via `git commit -F -`,
  a message file, or repeated `-m` flags; never embed escaped `\n` sequences and
  expect Git to render them as real line breaks.
- Call out user-facing behavior/schema changes and migrations.
- Preserve existing trailers and keep trailers in canonical git format (`Key: Value`) at the end of the message.
- Avoid vague messages like "misc" or "wip".

Example:

```
refactor(batch): migrate to Spring Batch 6.x package structure

- Update all imports from org.springframework.batch.item.* to batch.infrastructure.item.*
- Replace deprecated chunk(int, TxManager) with chunk(int).transactionManager(tm)
- Switch from JobLauncherTestUtils to JobOperatorTestUtils
```
