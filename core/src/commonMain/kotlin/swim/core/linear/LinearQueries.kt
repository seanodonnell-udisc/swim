package swim.core.linear

// Swim inlines every nested field. Linear's SDK loads each relationship with its own request.
// That cost approximately one request for each field of each issue. One request for each page
// of 250 issues replaces it.
internal const val ISSUE_NODE_FIELDS: String = """
  id
  identifier
  title
  priority
  estimate
  description
  url
  createdAt
  updatedAt
  state { name type }
  team { key }
  assignee { id name }
  project { name }
  labels { nodes { name } }
  attachments(first: 20) { nodes { url title } }
"""

// `relations` holds only the relations this issue owns. Only `inverseRelations` shows the
// blockers of this issue. The client reads both directions and removes the repeated relations.
internal const val RELATION_FIELDS: String = """
  relations(first: 100) {
    nodes { id type relatedIssue { id identifier title state { name type } } }
  }
  inverseRelations(first: 100) {
    nodes { id type issue { id identifier title state { name type } } }
  }
"""

internal const val ISSUES_QUERY: String = """query Issues(${'$'}filter: IssueFilter, ${'$'}first: Int, ${'$'}after: String) {
  issues(filter: ${'$'}filter, first: ${'$'}first, after: ${'$'}after) {
    nodes { $ISSUE_NODE_FIELDS }
    pageInfo { hasNextPage endCursor }
  }
}"""

internal const val ISSUES_WITH_RELATIONS_QUERY: String =
    """query IssuesWithRelations(${'$'}filter: IssueFilter, ${'$'}first: Int, ${'$'}after: String) {
  issues(filter: ${'$'}filter, first: ${'$'}first, after: ${'$'}after) {
    nodes {
      $ISSUE_NODE_FIELDS
      $RELATION_FIELDS
    }
    pageInfo { hasNextPage endCursor }
  }
}"""

// $number is Float, not Int. Linear's schema says so and an Int here is a hard error.
internal const val ISSUE_BY_IDENTIFIER_QUERY: String =
    """query IssueByIdentifier(${'$'}teamKey: String!, ${'$'}number: Float!) {
  issues(filter: { team: { key: { eq: ${'$'}teamKey } }, number: { eq: ${'$'}number } }, first: 1) {
    nodes {
      $ISSUE_NODE_FIELDS
      $RELATION_FIELDS
    }
  }
}"""

internal const val ISSUE_ID_BY_IDENTIFIER_QUERY: String =
    """query IssueIdByIdentifier(${'$'}teamKey: String!, ${'$'}number: Float!) {
  issues(filter: { team: { key: { eq: ${'$'}teamKey } }, number: { eq: ${'$'}number } }, first: 1) {
    nodes { id identifier }
  }
}"""

internal const val ISSUE_BY_UUID_QUERY: String = """query IssueById(${'$'}id: String!) {
  issue(id: ${'$'}id) { id identifier }
}"""

internal const val TEAMS_QUERY: String = """query Teams { teams { nodes { id key name } } }"""

internal const val PROJECTS_QUERY: String = """query Projects { projects { nodes { id name state } } }"""

internal const val PROJECTS_BY_TEAM_QUERY: String = """query ProjectsByTeam(${'$'}teamId: ID!) {
  projects(filter: { accessibleTeams: { id: { eq: ${'$'}teamId } } }) { nodes { id name state } }
}"""

internal const val LABELS_QUERY: String = """query Labels(${'$'}first: Int, ${'$'}after: String) {
  issueLabels(first: ${'$'}first, after: ${'$'}after) {
    nodes { id name color team { key } }
    pageInfo { hasNextPage endCursor }
  }
}"""

internal const val LABELS_BY_TEAM_QUERY: String =
    """query LabelsByTeam(${'$'}teamId: ID!, ${'$'}first: Int, ${'$'}after: String) {
  issueLabels(filter: { team: { id: { eq: ${'$'}teamId } } }, first: ${'$'}first, after: ${'$'}after) {
    nodes { id name color team { key } }
    pageInfo { hasNextPage endCursor }
  }
}"""

// Page size 50, not 250: the nested teams connection multiplies GraphQL complexity and 250
// exceeds Linear's per-query complexity cap.
internal const val PROJECT_SUMMARIES_QUERY: String = """query ProjectSummaries(${'$'}first: Int, ${'$'}after: String) {
  projects(first: ${'$'}first, after: ${'$'}after, filter: { state: { nin: ["completed", "canceled"] } }) {
    nodes { id name state teams(first: 10) { nodes { key } } }
    pageInfo { hasNextPage endCursor }
  }
}"""

internal const val USERS_QUERY: String = """query Users {
  users(first: 250, filter: { active: { eq: true } }) { nodes { id name } }
}"""

internal const val VIEWER_QUERY: String = """query Viewer { viewer { name email } }"""

internal const val CREATE_RELATION_MUTATION: String =
    """mutation CreateRelation(${'$'}issueId: String!, ${'$'}relatedIssueId: String!, ${'$'}type: IssueRelationType!) {
  issueRelationCreate(input: { issueId: ${'$'}issueId, relatedIssueId: ${'$'}relatedIssueId, type: ${'$'}type }) {
    success
    issueRelation { id }
  }
}"""

internal const val DELETE_RELATION_MUTATION: String = """mutation DeleteRelation(${'$'}id: String!) {
  issueRelationDelete(id: ${'$'}id) { success }
}"""

internal const val UPDATE_ISSUE_MUTATION: String =
    """mutation UpdateIssue(${'$'}id: String!, ${'$'}input: IssueUpdateInput!) {
  issueUpdate(id: ${'$'}id, input: ${'$'}input) { success }
}"""
