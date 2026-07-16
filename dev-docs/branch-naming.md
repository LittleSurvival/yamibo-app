# Branch Naming Rules

## First-Level Feature Branches

Use this pattern when a branch starts from a release/version base commit:

```text
{version-name}-{base-sha}-{module-propose-name}
```

Example:

```text
stable-v0.0.3-87c4229-favorite-batch-download
```

Rules:

- `{version-name}` is the full version name, such as `stable-v0.0.3`.
- `{base-sha}` is the commit used as the branch base.
- `{module-propose-name}` is a short kebab-case feature or OpenSpec change name.

## Nested Branches

Use this pattern for second-level, third-level, or deeper branches that continue from earlier feature branches:

```text
{version-name}-{branch1}-{level1-sha}-{level2-sha}-.....-{module-propose-name}
```

Rules:

- `{branch1}` is the first feature branch lineage name segment after the version name.
- `{level1-sha}`, `{level2-sha}`, and later sha segments identify the commits that each nested branch level is based on.
- Append one sha segment for each additional branch level.
- Keep `{module-propose-name}` as the final segment so the active work remains readable.

Example:

```text
stable-v0.0.3-favorite-batch-download-87c4229-03fa4d5-download-dialog-polish
```
