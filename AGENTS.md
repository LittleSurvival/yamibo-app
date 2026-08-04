# Repository Instructions

## OpenSpec isolation

- Never merge any file or change under `openspec/` into the `main` branch.
- Before merging into `main`, run `git diff --name-only main...HEAD -- openspec/` and require empty output.
- If a feature branch contains both implementation and `openspec/` changes, exclude the `openspec/` changes from the commits or history merged into `main`.

## Branch naming isolation

- Before creating any branch, read [Branch Naming Rule Document](dev-docs/branch-naming.md).

## Git identity guard

- Before every commit, run `git config --get user.name` and require the exact value `TheNano`.
- Before every push, inspect every outgoing commit and require both its author name and committer name to be exactly `TheNano`.
- If either check fails, do not commit or push; correct the Git identity or rewrite the affected commits first.
