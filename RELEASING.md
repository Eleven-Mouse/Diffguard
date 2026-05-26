# Releasing DiffGuard

This document defines the release process for sharing DiffGuard as a reusable GitHub Action and CLI JAR.

## Versioning Policy

- Follow SemVer: `vMAJOR.MINOR.PATCH`
- Examples:
  - `v1.0.0` (stable release)
  - `v1.1.0` (new backward-compatible features)
  - `v1.1.1` (bug fixes)
  - `v2.0.0` (breaking changes)
- Pre-release suffix is allowed:
  - `v1.2.0-rc.1`
  - `v1.2.0-beta.2`

## What the Release Workflow Does

Workflow file: `.github/workflows/release.yml`

Trigger conditions:
- Push a tag like `v1.2.3`
- Manual trigger from GitHub Actions UI with an existing tag

Pipeline:
1. Validate tag format
2. Run Java tests (`mvn -B verify`)
3. Run Python tests (`uv run pytest`)
4. Build shaded CLI JAR
5. Package assets:
   - `diffguard-<tag>.jar`
   - `diffguard-<tag>.jar.sha256`
6. Create (or update) GitHub Release and upload assets
7. Update release badges in `README.md` and `README.zh-CN.md` on default branch

## Recommended Release Steps

1. Ensure `main` is green and up to date.
2. Create and push release tag:

```bash
git checkout main
git pull --ff-only
git tag -a v1.0.0 -m "Release v1.0.0"
git push origin v1.0.0
```

3. Wait for `Release` workflow to complete.
4. Verify GitHub Release assets exist (`.jar` and `.sha256`).
5. Move major alias tag for Action users:

```bash
git tag -f v1 v1.0.0
git push -f origin v1
```

## How Users Consume DiffGuard Action

Users can pin to major tag:

```yaml
- name: DiffGuard Review
  uses: <your-org-or-user>/diffguard@v1
  with:
    api-key: ${{ secrets.DIFFGUARD_API_KEY }}
```

Or pin to exact release:

```yaml
- name: DiffGuard Review
  uses: <your-org-or-user>/diffguard@v1.0.0
  with:
    api-key: ${{ secrets.DIFFGUARD_API_KEY }}
```

## Manual Release from UI (Optional)

If tag already exists:
1. Go to `Actions` -> `Release`
2. Click `Run workflow`
3. Input tag (example: `v1.0.0`)
4. Start run

## Notes

- The workflow requires `contents: write` permission to create releases.
- If a Release already exists for a tag, assets are replaced using `--clobber`.
- GitHub Action users should prefer `@v1` for stable upgrades with backward compatibility.
