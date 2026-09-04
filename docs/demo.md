# Demo mode

Demo mode lets you show Swim's pull-request features. You need no GitHub account.
You need no real pull request.

## The demo data

Demo data is a set of sample issues and sample pull requests. The sample data has no real
company names. It has no real people. `tools/seed-demo.py` writes the sample issues to a
Linear workspace. Run this command to seed the workspace:

```
python3 tools/seed-demo.py
```

The script also writes a JSON file. This file holds the sample pull-request data.

## Turn on demo mode

Set the `SWIM_DEMO_PRS` environment variable. Point it at the JSON file the script wrote.

```
export SWIM_DEMO_PRS=/path/to/demo-prs.json
```

Start Swim with this variable set. Swim reads pull-request status from the file. Swim shows
PR chips, PR stacks, and relations derived from those stacks. The "Derive relations from PRs"
toggle turns on. You need no GitHub token for this.

## What the file looks like

The file maps a pull-request URL to its status fields. Every field is optional.

```json
{
  "https://github.com/octo-org/octo-repo/pull/101": {
    "headRefName": "feat/ingest-api",
    "baseRefName": "main",
    "reviewDecision": "APPROVED",
    "checkState": "SUCCESS"
  }
}
```

A pull-request URL absent from the file shows no status. A missing or broken file makes Swim
fall back to the real GitHub client. Swim never crashes over a bad demo file.

Demo mode never contacts GitHub.
