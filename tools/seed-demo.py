#!/usr/bin/env python3
"""Seed demo data for Swim into a Linear team.

The script creates three demo projects and fills them with fake issues,
milestones, relations, and pull-request attachments. The data set shows every
Swim feature: milestone lanes, blocker chains, fan-out and fan-in, diamonds,
cycles, duplicates, canceled blockers, cross-project blockers, and pull-request
stacks.

Every issue title starts with the tag "[demo]". The script deletes each "[demo]"
issue and each of the three demo projects before it writes new data, so a second
run replaces the first.

The script reads the API key from the environment variable LINEAR_API_KEY. It
never prints the key and never writes the key to a file.

Usage:
    export LINEAR_API_KEY=lin_api_...
    python3 tools/seed-demo.py --team SEA
    python3 tools/seed-demo.py --team SEA --wipe-only
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request

API_URL = "https://api.linear.app/graphql"
TAG = "[demo]"
REPO = "https://github.com/nimbus-labs/platform"

NIMBUS = "Nimbus Platform"
TANGLE = "Tangle Rescue"
ATLAS = "Atlas Migration"
PROJECTS = (NIMBUS, TANGLE, ATLAS)


# --- transport ---------------------------------------------------------------


class Api:
    """A minimal Linear GraphQL client. The key stays in this object."""

    def __init__(self, key: str) -> None:
        self._key = key
        self.calls = 0

    def __call__(self, query: str, variables: dict | None = None) -> dict:
        body = json.dumps({"query": query, "variables": variables or {}}).encode()
        request = urllib.request.Request(
            API_URL,
            data=body,
            headers={"Authorization": self._key, "Content-Type": "application/json"},
        )
        for attempt in range(5):
            try:
                with urllib.request.urlopen(request, timeout=60) as response:
                    payload = json.load(response)
                break
            except urllib.error.HTTPError as error:
                # 429 is the rate limit and 5xx is a server fault. Both clear with time.
                if error.code in (429, 500, 502, 503, 504) and attempt < 4:
                    time.sleep(2 ** attempt)
                    continue
                raise SystemExit(f"Linear answered HTTP {error.code}: {error.read()[:400]!r}")
        else:
            raise SystemExit("Linear did not answer.")
        self.calls += 1
        if "errors" in payload:
            raise SystemExit(f"Linear rejected the request: {json.dumps(payload['errors'])[:600]}")
        return payload["data"]


# --- the data set ------------------------------------------------------------

# (title, state, priority, estimate)
NIMBUS_ISSUES = {
    "Foundations": [
        ("Design the event schema", "Done", 2, 3),
        ("Spike the auth model", "Done", 2, 2),
        ("Stand up CI", "Done", 3, 2),
        ("Write dev seed data", "In Progress", 3, 2),
    ],
    "Service": [
        ("Build the ingest API", "In Progress", 1, 5),
        ("Build the query API", "Todo", 1, 5),
        ("Add a rate limiter", "Todo", 2, 3),
        ("Add a cache layer", "Backlog", 3, 3),
        ("Export metrics", "Backlog", 3, 2),
    ],
    "Launch": [
        ("Build the web dashboard", "Backlog", 2, 8),
        ("Build the mobile view", "Backlog", 3, 5),
        ("Write the docs site", "Backlog", 4, 3),
        ("Design the pricing page", "Backlog", 4, 2),
        ("Run the launch checklist", "Backlog", 1, 1),
    ],
}

# The rate limiter has no Linear edge from the ingest API on purpose. The
# pull-request data derives that edge, which is the point of the demo.
NIMBUS_RELATIONS = [
    ("blocks", "Design the event schema", "Build the ingest API"),
    ("blocks", "Build the ingest API", "Build the query API"),
    ("blocks", "Build the ingest API", "Add a cache layer"),
    ("blocks", "Build the ingest API", "Export metrics"),
    ("blocks", "Build the query API", "Build the web dashboard"),
    ("blocks", "Add a cache layer", "Build the web dashboard"),
    ("blocks", "Build the query API", "Build the mobile view"),
    ("blocks", "Build the web dashboard", "Run the launch checklist"),
    ("blocks", "Write the docs site", "Run the launch checklist"),
    ("blocks", "Design the pricing page", "Run the launch checklist"),
    ("related", "Spike the auth model", "Add a rate limiter"),
]

TANGLE_ISSUES = [
    ("Runtime waits on config", "Backlog", 2, 2),
    ("Config waits on the loader", "Backlog", 2, 2),
    ("Loader waits on the runtime", "Backlog", 2, 2),
    ("Login redirect loops", "Backlog", 3, 1),
    ("Fix the login redirect", "Todo", 2, 2),
    ("Retire the old client", "Canceled", 0, 3),
    ("Ship the new client", "Todo", 2, 5),
    ("Audit the error codes", "In Progress", 2, 3),
    ("Publish the error reference", "Backlog", 3, 2),
    ("Wire the rescue dashboard", "Backlog", 3, 3),
]

TANGLE_RELATIONS = [
    # A cycle of three. Linear collapses a cycle of two, so three is the minimum.
    ("blocks", "Runtime waits on config", "Config waits on the loader"),
    ("blocks", "Config waits on the loader", "Loader waits on the runtime"),
    ("blocks", "Loader waits on the runtime", "Runtime waits on config"),
    # The first issue is the duplicate side.
    ("duplicate", "Login redirect loops", "Fix the login redirect"),
    # A canceled blocker counts as done, so the blocked issue reads ready.
    ("blocks", "Retire the old client", "Ship the new client"),
    ("blocks", "Audit the error codes", "Publish the error reference"),
    ("related", "Ship the new client", "Fix the login redirect"),
    ("related", "Audit the error codes", "Runtime waits on config"),
]

# One blocker that lives in another project.
CROSS_PROJECT_RELATIONS = [
    ("blocks", (NIMBUS, "Build the query API"), (TANGLE, "Wire the rescue dashboard")),
]

ATLAS_THINGS = [
    "the billing service", "the user profiles", "the session store",
    "the email templates", "the audit log", "the feature flags",
    "the image pipeline", "the search index", "the notification queue",
    "the report exports", "the admin console", "the API gateway",
    "the cron jobs", "the config store", "the error tracking",
    "the analytics events", "the payment webhooks", "the file uploads",
    "the access control", "the localization files", "the health checks",
    "the log shipping", "the secret storage", "the cache warmers",
    "the data backups", "the schema registry", "the test fixtures",
    "the staging data", "the legacy redirects", "the contact forms",
]

ATLAS_CHAINS = ["accounts", "catalog", "media", "telemetry"]
ATLAS_STAGES = ["freeze the writes", "copy the data", "verify the copy", "cut over"]

ATLAS_FANOUTS = [
    ("Publish the shared client library",
     ["Adopt the shared client in {}".format(name) for name in
      ("search", "billing", "media", "reports", "alerts", "exports")]),
    ("Freeze the legacy schema",
     ["Rewrite the {} queries".format(name) for name in
      ("account", "invoice", "asset", "session", "webhook", "audit")]),
]

# States, priorities, and estimates cycle so the demo spreads across every value.
ATLAS_STATES = ["Backlog", "Todo", "In Progress", "In Review", "Done"]
ATLAS_PRIORITIES = [0, 1, 2, 3, 4]
ATLAS_ESTIMATES = [1, 2, 3, 5, 8]

# (project, issue title, number, head branch, base branch, review, checks)
PULL_REQUESTS = [
    (NIMBUS, "Build the ingest API", 101, "feat/ingest-api", "main", "APPROVED", "SUCCESS"),
    (NIMBUS, "Build the query API", 102, "feat/query-api", "feat/ingest-api", "REVIEW_REQUIRED", "PENDING"),
    (NIMBUS, "Add a rate limiter", 103, "feat/rate-limiter", "feat/ingest-api", "CHANGES_REQUESTED", "FAILURE"),
    (NIMBUS, "Export metrics", 104, "feat/metrics", "main", "APPROVED", "SUCCESS"),
    (NIMBUS, "Write dev seed data", 105, "feat/metrics", "main", "APPROVED", "SUCCESS"),
    (NIMBUS, "Build the web dashboard", 106, "feat/dashboard", "feat/query-api", None, None),
    (TANGLE, "Fix the login redirect", 201, "fix/login-redirect", "main", "APPROVED", "FAILURE"),
]


# --- Linear operations -------------------------------------------------------


def resolve_team(api: Api, key: str) -> dict:
    data = api(
        "query Team($key: String!) {"
        "  teams(filter: { key: { eq: $key } }, first: 1) {"
        "    nodes { id key name states { nodes { id name type } } } } }",
        {"key": key},
    )
    nodes = data["teams"]["nodes"]
    if not nodes:
        raise SystemExit(f"No team has the key {key}.")
    return nodes[0]


def state_lookup(team: dict) -> dict:
    """Maps a wanted state name to a state id, with the nearest type as the fallback."""
    states = team["states"]["nodes"]
    by_name = {s["name"]: s["id"] for s in states}
    by_type = {}
    for state in states:
        by_type.setdefault(state["type"], state["id"])
    fallback_type = {
        "Backlog": "backlog", "Todo": "unstarted", "In Progress": "started",
        "In Review": "started", "Paused": "started", "Done": "completed",
        "Canceled": "canceled", "Duplicate": "duplicate",
    }

    def resolve(name: str) -> str:
        if name in by_name:
            return by_name[name]
        wanted = fallback_type.get(name)
        if wanted and wanted in by_type:
            print(f"  note: the team has no state {name!r}; using its {wanted} state")
            return by_type[wanted]
        raise SystemExit(f"The team has no state for {name!r}.")

    return {name: resolve(name) for name in fallback_type}


def wipe(api: Api, team_id: str) -> tuple[int, int]:
    """Deletes every demo issue in the team and the three demo projects."""
    issues = []
    cursor = None
    while True:
        data = api(
            "query DemoIssues($team: ID!, $after: String) {"
            "  issues(filter: { team: { id: { eq: $team } } }, first: 100, after: $after) {"
            "    nodes { id title } pageInfo { hasNextPage endCursor } } }",
            {"team": team_id, "after": cursor},
        )
        page = data["issues"]
        issues += [n for n in page["nodes"] if n["title"].startswith(TAG)]
        if not page["pageInfo"]["hasNextPage"]:
            break
        cursor = page["pageInfo"]["endCursor"]

    for issue in issues:
        api("mutation Del($id: String!) { issueDelete(id: $id) { success } }", {"id": issue["id"]})

    data = api(
        "query DemoProjects($team: ID!) {"
        "  projects(filter: { accessibleTeams: { id: { eq: $team } } }, first: 100) {"
        "    nodes { id name } } }",
        {"team": team_id},
    )
    targets = [p for p in data["projects"]["nodes"] if p["name"] in PROJECTS]
    for project in targets:
        api("mutation Del($id: String!) { projectDelete(id: $id) { success } }", {"id": project["id"]})
    return len(issues), len(targets)


def create_project(api: Api, team_id: str, name: str, description: str) -> str:
    data = api(
        "mutation NewProject($input: ProjectCreateInput!) {"
        "  projectCreate(input: $input) { project { id } } }",
        {"input": {"name": name, "description": description, "teamIds": [team_id]}},
    )
    return data["projectCreate"]["project"]["id"]


def create_milestone(api: Api, project_id: str, name: str, order: int) -> str:
    data = api(
        "mutation NewMilestone($input: ProjectMilestoneCreateInput!) {"
        "  projectMilestoneCreate(input: $input) { projectMilestone { id } } }",
        {"input": {"name": name, "projectId": project_id, "sortOrder": float(order)}},
    )
    return data["projectMilestoneCreate"]["projectMilestone"]["id"]


def create_issue(api: Api, team_id: str, project_id: str, state_id: str, title: str,
                 priority: int, estimate: int | None, milestone_id: str | None) -> dict:
    payload = {
        "teamId": team_id,
        "projectId": project_id,
        "stateId": state_id,
        "title": f"{TAG} {title}",
        "priority": priority,
    }
    if estimate is not None:
        payload["estimate"] = estimate
    if milestone_id is not None:
        payload["projectMilestoneId"] = milestone_id
    data = api(
        "mutation NewIssue($input: IssueCreateInput!) {"
        "  issueCreate(input: $input) { issue { id identifier } } }",
        {"input": payload},
    )
    return data["issueCreate"]["issue"]


def create_relation(api: Api, issue_id: str, related_id: str, kind: str) -> None:
    api(
        "mutation NewRelation($input: IssueRelationCreateInput!) {"
        "  issueRelationCreate(input: $input) { success } }",
        {"input": {"issueId": issue_id, "relatedIssueId": related_id, "type": kind}},
    )


def attach_url(api: Api, issue_id: str, url: str, title: str) -> None:
    api(
        "mutation Attach($issueId: String!, $url: String!, $title: String!) {"
        "  attachmentLinkURL(issueId: $issueId, url: $url, title: $title) { success } }",
        {"issueId": issue_id, "url": url, "title": title},
    )


# --- the seed ----------------------------------------------------------------


def seed(api: Api, team: dict, states: dict) -> dict:
    team_id = team["id"]
    issues: dict[tuple[str, str], dict] = {}

    def add(project_name, project_id, title, state, priority, estimate, milestone=None):
        issue = create_issue(api, team_id, project_id, states[state], title,
                             priority, estimate, milestone)
        issues[(project_name, title)] = issue

    print(f"Creating {NIMBUS} ...")
    nimbus = create_project(api, team_id, NIMBUS,
                            "Demo data for Swim. Milestone lanes, a pull-request stack, "
                            "and derived blockers.")
    for order, (milestone_name, rows) in enumerate(NIMBUS_ISSUES.items()):
        milestone_id = create_milestone(api, nimbus, milestone_name, order)
        for title, state, priority, estimate in rows:
            add(NIMBUS, nimbus, title, state, priority, estimate, milestone_id)

    print(f"Creating {TANGLE} ...")
    tangle = create_project(api, team_id, TANGLE,
                            "Demo data for Swim. A blocker cycle, a duplicate, a canceled "
                            "blocker, and a blocker in another project.")
    for title, state, priority, estimate in TANGLE_ISSUES:
        add(TANGLE, tangle, title, state, priority, estimate)

    print(f"Creating {ATLAS} ...")
    atlas = create_project(api, team_id, ATLAS,
                           "Demo data for Swim. Many independent tasks, four chains, and "
                           "two fan-outs.")
    atlas_relations = []
    for index, thing in enumerate(ATLAS_THINGS):
        add(ATLAS, atlas, f"Migrate {thing}", ATLAS_STATES[index % 5],
            ATLAS_PRIORITIES[index % 5], ATLAS_ESTIMATES[index % 5])
    for chain_index, topic in enumerate(ATLAS_CHAINS):
        previous = None
        for stage_index, stage in enumerate(ATLAS_STAGES):
            title = f"Stage {stage_index + 1}: {stage} for {topic}"
            add(ATLAS, atlas, title, ATLAS_STATES[(chain_index + stage_index) % 5],
                ATLAS_PRIORITIES[stage_index % 5], ATLAS_ESTIMATES[(chain_index + stage_index) % 5])
            if previous:
                atlas_relations.append(("blocks", previous, title))
            previous = title
    for fan_index, (source, targets) in enumerate(ATLAS_FANOUTS):
        add(ATLAS, atlas, source, "In Progress", 1, 5)
        for target_index, target in enumerate(targets):
            add(ATLAS, atlas, target, ATLAS_STATES[target_index % 5],
                ATLAS_PRIORITIES[(target_index + fan_index) % 5],
                ATLAS_ESTIMATES[target_index % 5])
            atlas_relations.append(("blocks", source, target))

    print("Creating the relations ...")
    plan = (
        [(kind, (NIMBUS, a), (NIMBUS, b)) for kind, a, b in NIMBUS_RELATIONS]
        + [(kind, (TANGLE, a), (TANGLE, b)) for kind, a, b in TANGLE_RELATIONS]
        + [(kind, (ATLAS, a), (ATLAS, b)) for kind, a, b in atlas_relations]
        + CROSS_PROJECT_RELATIONS
    )
    for kind, source, target in plan:
        create_relation(api, issues[source]["id"], issues[target]["id"], kind)

    print("Attaching the pull requests ...")
    pr_statuses = {}
    for project_name, title, number, head, base, review, checks in PULL_REQUESTS:
        url = f"{REPO}/pull/{number}"
        attach_url(api, issues[(project_name, title)]["id"], url, f"#{number} {head}")
        status = {"headRefName": head, "baseRefName": base}
        if review is not None:
            status["reviewDecision"] = review
        if checks is not None:
            status["checkState"] = checks
        pr_statuses[url] = status

    return {"issues": issues, "relations": len(plan), "prs": pr_statuses}


def config_dir() -> str:
    """The directory Swim keeps its own data in. macOS uses Library; the rest follow XDG."""
    home = os.path.expanduser("~")
    if sys.platform == "darwin":
        return os.path.join(home, "Library", "Application Support", "swim")
    base = os.environ.get("XDG_CONFIG_HOME") or os.path.join(home, ".config")
    return os.path.join(base, "swim")


def main() -> None:
    parser = argparse.ArgumentParser(description="Seed demo data for Swim into a Linear team.")
    parser.add_argument("--team", default="SEA", help="the key of the team to write to")
    parser.add_argument("--wipe-only", action="store_true",
                        help="delete the demo data and stop")
    parser.add_argument("--out", default=None,
                        help="where to write the pull-request status JSON "
                             "(default: the Swim config directory)")
    args = parser.parse_args()

    key = os.environ.get("LINEAR_API_KEY")
    if not key:
        raise SystemExit("Set LINEAR_API_KEY in the environment.")

    api = Api(key)
    team = resolve_team(api, args.team)
    print(f"Team {team['key']} ({team['name']})")
    states = state_lookup(team)

    deleted_issues, deleted_projects = wipe(api, team["id"])
    print(f"Deleted {deleted_issues} demo issues and {deleted_projects} demo projects.")
    if args.wipe_only:
        return

    result = seed(api, team, states)

    out = args.out or os.path.join(config_dir(), "demo-prs.json")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w") as handle:
        json.dump(result["prs"], handle, indent=2, sort_keys=True)
        handle.write("\n")

    issues = result["issues"]
    print()
    for name in PROJECTS:
        rows = [(t, i) for (p, t), i in issues.items() if p == name]
        print(f"{name}: {len(rows)} issues")
        for title, issue in rows:
            print(f"  {issue['identifier']}  {title}")
    print()
    print(f"Issues:        {len(issues)}")
    print(f"Relations:     {result['relations']}")
    print(f"Pull requests: {len(result['prs'])}")
    print(f"API calls:     {api.calls}")
    print(f"PR status JSON: {out}")


if __name__ == "__main__":
    main()
