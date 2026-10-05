# Branch Naming Rules

## Tagged Branch Bases

- Before creating a branch, check whether its base commit has a tag with `git tag --points-at <base-ref>`.
- If the base commit has a tag, omit the hash segment for that branching level.
- If the base commit has no tag, include its hash as described below.
- A tag on an older ancestor does not qualify; the tag must point directly to the commit used as the new branch's base.

## First-Level Feature Branches

When the release/version base commit has a tag, use:

```text
{version-name}-{module-propose-name}
```

Example:

```text
stable-v0.0.8-fix-search-formhash
```

When the base commit has no tag, use:

```text
{version-name}-{base-sha}-{module-propose-name}
```

Example:

```text
stable-v0.0.3-87c4229-favorite-batch-download
```

Rules:

- `{version-name}` is the full version name, such as `stable-v0.0.3`.
- `{base-sha}` identifies the commit used as the branch base and is included only when that commit has no tag.
- `{module-propose-name}` is a short kebab-case feature or OpenSpec change name.

## Nested Branches

Use this pattern for second-level, third-level, or deeper branches that continue from earlier feature branches:

```text
{version-name}-{branch1}-{level1-sha}-{level2-sha}-.....-{module-propose-name}
```

Rules:

- `{branch1}` is the first feature branch lineage name segment after the version name.
- `{level1-sha}`, `{level2-sha}`, and later sha segments identify the commits that each nested branch level is based on.
- Include one sha segment for each branching level whose base commit has no tag; omit the segment when that base commit has a tag.
- Keep `{module-propose-name}` as the final segment so the active work remains readable.

Example:

```text
stable-v0.0.3-favorite-batch-download-87c4229-03fa4d5-download-dialog-polish
```

If the first-level base commit has a tag but the next-level base commit `03fa4d5` does not, omit only the first-level hash:

```text
stable-v0.0.3-favorite-batch-download-03fa4d5-download-dialog-polish
```
