package swim.cli.repo

import swim.cli.absolutePath
import swim.cli.baseName
import swim.cli.pathExists
import swim.cli.shell
import swim.cli.shellQuote
import swim.core.config.SwimConfig
import swim.core.model.ApiError
import swim.core.model.GapAnalysis
import swim.core.model.RepoReference

/** Every issue identifier that the source files of one git repository mention. */
fun findIssueReferences(repoPath: String, config: SwimConfig = SwimConfig()): List<RepoReference> {
    val absolute = absolutePath(repoPath)
    if (!pathExists("$absolute/.git")) throw ApiError("Not a git repository: $absolute")
    val repo = baseName(absolute)

    val globs = config.repoGlobs.joinToString(" ") { shellQuote(it) }
    val output = shell(
        "cd ${shellQuote(absolute)} && " +
            "git grep -n -E ${shellQuote(config.identifierPattern)} -- $globs || true"
    ).orEmpty()

    val identifier = Regex("\\b(${config.identifierPattern})\\b")
    val references = mutableListOf<RepoReference>()
    for (line in output.split("\n")) {
        val match = GREP_LINE.matchEntire(line) ?: continue
        val (file, lineNumber, content) = match.destructured
        for (hit in identifier.findAll(content)) {
            references.add(
                RepoReference(
                    repo = repo,
                    file = file,
                    line = lineNumber.toIntOrNull() ?: continue,
                    issueId = hit.groupValues[1],
                )
            )
        }
    }
    return references
}

/** The references of every repository, keyed by the repository directory name. */
fun findAllReferences(repoPaths: List<String>, config: SwimConfig = SwimConfig()): Map<String, List<RepoReference>> {
    val all = LinkedHashMap<String, List<RepoReference>>()
    for (path in repoPaths) all[baseName(absolutePath(path))] = findIssueReferences(path, config)
    return all
}

/** The references of one issue, in the order they were found. */
fun groupReferencesByIssue(references: List<RepoReference>): Map<String, List<RepoReference>> {
    val grouped = LinkedHashMap<String, MutableList<RepoReference>>()
    for (reference in references) grouped.getOrPut(reference.issueId) { mutableListOf() }.add(reference)
    return grouped
}

/** Issues that some repositories mention and others do not. */
fun analyzeGaps(
    allReferences: Map<String, List<RepoReference>>,
    issues: List<Pair<String, String>>,
): List<GapAnalysis> {
    val repos = allReferences.keys.toList()
    val issueToRepos = LinkedHashMap<String, MutableSet<String>>()
    for ((repo, references) in allReferences) {
        for (reference in references) issueToRepos.getOrPut(reference.issueId) { LinkedHashSet() }.add(repo)
    }

    return issues.mapNotNull { (identifier, title) ->
        val referencedIn = issueToRepos[identifier]
        if (referencedIn == null || referencedIn.size == repos.size) {
            null
        } else {
            GapAnalysis(
                identifier = identifier,
                title = title,
                referencedIn = referencedIn.toList(),
                missingIn = repos.filterNot { it in referencedIn },
            )
        }
    }
}

/** References to identifiers that Linear does not know. */
fun findOrphanedReferences(
    allReferences: Map<String, List<RepoReference>>,
    validIdentifiers: Set<String>,
): List<RepoReference> = allReferences.values.flatten().filterNot { it.issueId in validIdentifiers }

private val GREP_LINE = Regex("^([^:]+):(\\d+):(.+)$")
