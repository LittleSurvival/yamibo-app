# Repository scripts

Keep scripts in a folder named for their purpose. Run commands from the
repository root unless a script documents another working directory.

| Folder | Purpose |
| --- | --- |
| `apple-validate/` | Apple simulator validation conventions; scenario scripts remain on validation branches. |
| `checks/` | Git identity, production test isolation, and logger coverage checks. |
| `release/` | Release assets, upload progress, update feeds, and mirror publishing. |
| `server-notice/` | Server notice generation. |

When moving a script, update workflows, script-to-script calls, documentation,
IDE run configurations, and any paths derived from the script's own location.
